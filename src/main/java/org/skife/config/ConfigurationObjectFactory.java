package org.skife.config;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.TypeCache;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import static java.util.Collections.synchronizedMap;

public class ConfigurationObjectFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationObjectFactory.class);
    private static final TypeCache<Class<?>> typeCache = new TypeCache<Class<?>>(TypeCache.Sort.WEAK);
    private static final String INTERCEPTORS_FIELD_NAME = "___interceptors___";
    private static final Map<Class<?>, Field> interceptorsFieldsCache = synchronizedMap(new WeakHashMap<Class<?>, Field>());
    private static final Object monitor = new Object();

    private final ConfigSource config;
    private final Bully bully;
    private final Logger buildLogger;
    private final Level buildLogLevel;

    public ConfigurationObjectFactory(Properties props) {
        this(new SimplePropertyConfigSource(props), LoggerFactory.getLogger(ConfigurationObjectFactory.class), Level.INFO);
    }

    public ConfigurationObjectFactory(Properties props, Logger buildLogger, Level buildLogLevel) {
        this(new SimplePropertyConfigSource(props), buildLogger, buildLogLevel);
    }

    public ConfigurationObjectFactory(ConfigSource config) {
        this(config, LoggerFactory.getLogger(ConfigurationObjectFactory.class), Level.INFO);
    }

    public ConfigurationObjectFactory(ConfigSource config, Logger buildLogger, Level buildLogLevel) {
        this.config = config;
        this.bully = new Bully();
        this.buildLogger = buildLogger;
        this.buildLogLevel = buildLogLevel;
    }

    public void addCoercible(final Coercible<?> coercible) {
        this.bully.addCoercible(coercible);
    }


    public <T> T buildWithReplacements(Class<T> configClass, Map<String, String> mappedReplacements) {
        return internalBuild(configClass, mappedReplacements);
    }

    public <T> T build(Class<T> configClass) {
        return internalBuild(configClass, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T internalBuild(final Class<T> configClass, Map<String, String> mappedReplacements) {
        final Map<Method, Interceptor> interceptors = new HashMap<Method, Interceptor>();

        // Now hook up the actual value interceptors.
        for (final Method method : configClass.getMethods()) {
            if (method.isAnnotationPresent(Config.class)) {
                final Config annotation = method.getAnnotation(Config.class);

                if (method.getParameterTypes().length > 0) {
                    if (mappedReplacements != null) {
                        throw new RuntimeException("Replacements are not supported for parameterized config methods");
                    }
                    interceptors.put(method, buildParameterized(method, annotation));
                } else {
                    interceptors.put(method, buildSimple(method, annotation, mappedReplacements, null));
                }
            } else if (method.isAnnotationPresent(ConfigReplacements.class)) {
                final ConfigReplacements annotation = method.getAnnotation(ConfigReplacements.class);

                if (ConfigReplacements.DEFAULT_VALUE.equals(annotation.value())) {
                    Map<String, String> fixedMap = mappedReplacements == null ?
                            Collections.<String, String>emptyMap() : Collections.unmodifiableMap(mappedReplacements);

                    interceptors.put(method, new ConfigMagicFixedValue(method, "annotation: @ConfigReplacements", fixedMap));
                } else {
                    interceptors.put(method, buildSimple(method, null, mappedReplacements, annotation));
                }
            } else if (Modifier.isAbstract(method.getModifiers())) {
                throw new AbstractMethodError(String.format("Method [%s] is abstract and lacks an @Config annotation",
                        method.toGenericString()));
            }
        }


        try {
            Class<?> proxyClass = typeCache.findOrInsert(configClass.getClassLoader(), configClass, new Callable<Class<?>>() {
                @Override
                public Class<?> call() throws Exception {
                    ConfigMagicBeanToString toStringInterceptor = new ConfigMagicBeanToString();
                    // Hook up a toString method that prints out the settings for that bean if possible
                    DynamicType.Builder<T> builder = new ByteBuddy()
                            .subclass(configClass)
                            .defineField(INTERCEPTORS_FIELD_NAME, Map.class, Visibility.PUBLIC)
                            .method(ElementMatchers.isToString())
                            .intercept(MethodDelegation.to(toStringInterceptor));

                    for (Map.Entry<Method, Interceptor> e : interceptors.entrySet()) {
                        Object cb = e.getValue();
                        if (cb != null) {
                            builder = builder
                                    .method(ElementMatchers.is(e.getKey()))
                                    .intercept(MethodDelegation.to(Interceptor.class));
                        }
                    }
                    return builder
                            .make()
                            .load(getClass().getClassLoader(), resolveClassLoadingStrategy(configClass))
                            .getLoaded();
                }
            }, monitor);
            T instance = (T) proxyClass.newInstance();
            Field interceptorsField = interceptorsFieldsCache.get(proxyClass);
            if (interceptorsField == null) {
                interceptorsField = proxyClass.getField(INTERCEPTORS_FIELD_NAME);
                interceptorsFieldsCache.put(proxyClass, interceptorsField);
            }

            interceptorsField.set(instance, interceptors);
            return instance;
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    private ClassLoadingStrategy<ClassLoader> resolveClassLoadingStrategy(Class<?> targetClass) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ClassLoadingStrategy<ClassLoader> strategy;
        if (ClassInjector.UsingLookup.isAvailable()) {
            Class<?> methodHandles = Class.forName("java.lang.invoke.MethodHandles");
            Object lookup = methodHandles.getMethod("lookup").invoke(null);
            Method privateLookupIn = methodHandles.getMethod("privateLookupIn",
                    Class.class,
                    Class.forName("java.lang.invoke.MethodHandles$Lookup"));
            Object privateLookup = privateLookupIn.invoke(null, targetClass, lookup);
            strategy = ClassLoadingStrategy.UsingLookup.of(privateLookup);
        } else if (ClassInjector.UsingReflection.isAvailable()) {
            strategy = ClassLoadingStrategy.Default.INJECTION;
        } else {
            throw new IllegalStateException("No code generation strategy available");
        }
        return strategy;
    }

    private Interceptor buildSimple(Method method, Config annotation,
                                    Map<String, String> mappedReplacements, ConfigReplacements mapAnnotation) {
        String assignedFrom = null;
        String[] propertyNames = new String[0];
        String value = null;

        // Annotation will be null for an @ConfigReplacements, in which case "value" will
        // be preset and ready to be defaulted + bullied
        if (annotation != null) {
            propertyNames = annotation.value();

            if (propertyNames == null || propertyNames.length == 0) {
                throw new IllegalArgumentException("Method " +
                        method.toGenericString() +
                        " declares config annotation but no field name!");
            }


            for (String propertyName : propertyNames) {
                if (mappedReplacements != null) {
                    propertyName = applyReplacements(propertyName, mappedReplacements);
                }
                value = config.getString(propertyName);

                // First value found wins
                if (value != null) {
                    assignedFrom = "property: '" + propertyName + "'";
                    buildLog("Assigning value [{}] for [{}] on [{}#{}()]",
                            new Object[]{value, propertyName, method.getDeclaringClass().getName(), method.getName()});
                    break;
                }
            }
        } else {
            if (mapAnnotation == null) {
                throw new IllegalStateException("Neither @Config nor @ConfigReplacements provided, this should not be possible!");
            }
            String key = mapAnnotation.value();
            value = mappedReplacements == null ? null : mappedReplacements.get(key);

            if (value != null) {
                assignedFrom = "@ConfigReplacements: key '" + key + "'";
                buildLog("Assigning mappedReplacement value [{}] for [{}] on [{}#{}()]",
                        new Object[]{value, key, method.getDeclaringClass().getName(), method.getName()});
            }
        }

        final boolean hasDefault = method.isAnnotationPresent(Default.class);
        final boolean hasDefaultNull = method.isAnnotationPresent(DefaultNull.class);

        if (hasDefault && hasDefaultNull) {
            throw new IllegalArgumentException(String.format("@Default and @DefaultNull present in [%s]", method.toGenericString()));
        }


        //
        // This is how the value logic works if no value has been set by the config:
        //
        // - if the @Default annotation is present, use its value.
        // - if the @DefaultNull annotation is present, accept null as the value
        // - otherwise, check whether the method is not abstract. If it is not, return the callback that should call the method and
        //   ignore the passed in value (which will be null)
        // - if all else fails, throw an exception.
        //
        if (value == null) {
            if (hasDefault) {
                value = method.getAnnotation(Default.class).value();
                assignedFrom = "annotation: @Default";

                buildLog("Assigning default value [{}] for {} on [{}#{}()]",
                        new Object[]{value, propertyNames, method.getDeclaringClass().getName(), method.getName()});
            } else if (hasDefaultNull) {
                buildLog("Assigning null default value for {} on [{}#{}()]",
                        new Object[]{propertyNames, method.getDeclaringClass().getName(), method.getName()});
                assignedFrom = "annotation: @DefaultNull";
            } else {
                // Final try: Is the method is actually callable?
                if (!Modifier.isAbstract(method.getModifiers())) {
                    assignedFrom = "method: '" + method.getName() + "()'";
                    buildLog("Using method itself for {} on [{}#{}()]",
                            new Object[]{propertyNames, method.getDeclaringClass().getName(), method.getName()});
                    return new ConfigMagicSuperValue(method, assignedFrom);
                } else {
                    throw new IllegalArgumentException(String.format("No value present for '%s' in [%s]",
                            prettyPrint(propertyNames, mappedReplacements),
                            method.toGenericString()));
                }
            }
        }

        final Object finalValue = value == null && hasDefaultNull
                ? value
                : bully.coerce(method.getGenericReturnType(), value, method.getAnnotation(Separator.class));
        return new ConfigMagicFixedValue(method, assignedFrom, finalValue);
    }

    private void buildLog(String format, Object... arguments) {
        switch (buildLogLevel) {
            case TRACE:
                buildLogger.trace(format, arguments);
                break;
            case DEBUG:
                buildLogger.debug(format, arguments);
                break;
            case INFO:
                buildLogger.info(format, arguments);
                break;
            case WARN:
                buildLogger.warn(format, arguments);
                break;
            case ERROR:
                buildLogger.error(format, arguments);
                break;
        }
    }

    private String applyReplacements(String propertyName, Map<String, String> mappedReplacements) {
        for (String key : mappedReplacements.keySet()) {
            String token = makeToken(key);
            String replacement = mappedReplacements.get(key);
            propertyName = propertyName.replace(token, replacement);
        }
        return propertyName;
    }

    private Interceptor buildParameterized(Method method, Config annotation) {
        String defaultValue = null;

        final boolean hasDefault = method.isAnnotationPresent(Default.class);
        final boolean hasDefaultNull = method.isAnnotationPresent(DefaultNull.class);

        if (hasDefault && hasDefaultNull) {
            throw new IllegalArgumentException(String.format("@Default and @DefaultNull present in [%s]", method.toGenericString()));
        }

        if (hasDefault) {
            defaultValue = method.getAnnotation(Default.class).value();
        } else if (!hasDefaultNull) {
            throw new IllegalArgumentException(String.format("No value present for '%s' in [%s]",
                    prettyPrint(annotation.value(), null),
                    method.toGenericString()));
        }

        final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        final List<String> paramTokenList = new ArrayList<String>();
        for (Annotation[] parameterTab : parameterAnnotations) {
            for (Annotation parameter : parameterTab) {
                if (parameter.annotationType().equals(Param.class)) {
                    Param paramAnnotation = (Param) parameter;
                    paramTokenList.add(makeToken(paramAnnotation.value()));
                    break;
                }
            }
        }

        if (paramTokenList.size() != method.getParameterTypes().length) {
            throw new RuntimeException(String.format("Method [%s] is missing one or more @Param annotations",
                    method.toGenericString()));
        }

        final Object bulliedDefaultValue = bully.coerce(method.getGenericReturnType(), defaultValue, method.getAnnotation(Separator.class));
        final String[] annotationValues = annotation.value();

        if (annotationValues == null || annotationValues.length == 0) {
            throw new IllegalArgumentException("Method " +
                    method.toGenericString() +
                    " declares config annotation but no field name!");
        }

        return new ConfigMagicParametrizedValue(method,
                config,
                annotationValues,
                paramTokenList,
                bully,
                bulliedDefaultValue);
    }

    private String makeToken(String temp) {
        return "${" + temp + "}";
    }

    private String prettyPrint(String[] values, final Map<String, String> mappedReplacements) {
        if (values == null || values.length == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("[");

        for (int i = 0; i < values.length; i++) {
            sb.append(values[i]);
            if (i < (values.length - 1)) {
                sb.append(", ");
            }
        }
        sb.append(']');
        if (mappedReplacements != null && mappedReplacements.size() > 0) {
            sb.append(" translated to [");
            for (int i = 0; i < values.length; i++) {
                sb.append(applyReplacements(values[i], mappedReplacements));
                if (i < (values.length - 1)) {
                    sb.append(", ");
                }
            }
            sb.append("]");
        }

        return sb.toString();
    }

    public static abstract class Interceptor {
        @BindingPriority(9999)
        @RuntimeType
        public static Object intercept(@FieldValue(INTERCEPTORS_FIELD_NAME) Map<Method, Interceptor> interceptors,
                                       @Origin Method method,
                                       @AllArguments Object[] args,
                                       @SuperCall(nullIfImpossible = true) Callable<Object> superCall,
                                       @StubValue Object stub) throws Exception {
            Object res = interceptors.get(method).intercept(interceptors, args, superCall);
            return res == null ? stub : res;
        }

        protected abstract Object intercept(Map<Method, Interceptor> handlers,
                                            Object[] args,
                                            Callable<Object> superCall) throws Exception;
    }

    public static final class ConfigMagicSuperValue extends Interceptor {
        private final Method method;
        private final String assignedFrom;


        private ConfigMagicSuperValue(final Method method, final String assignedFrom) {
            this.method = method;
            this.assignedFrom = assignedFrom;
        }


        @Override
        protected Object intercept(Map<Method, Interceptor> interceptors, Object[] args, Callable<Object> superCall) throws Exception {
            return superCall.call();
        }

        private transient String toStringValue = null;

        @Override
        @IgnoreForBinding
        public String toString() {
            if (toStringValue == null) {
                final StringBuilder sb = new StringBuilder(method.getName());
                sb.append("(): ");
                sb.append(assignedFrom);
                sb.append(", ");
                toStringValue = sb.toString();
            }

            return toStringValue;
        }
    }


    public static final class ConfigMagicFixedValue extends Interceptor {
        private final Method method;
        private final String assignedFrom;
        private final Object value;


        private ConfigMagicFixedValue(final Method method, final String assignedFrom, final Object value) {
            this.method = method;
            this.assignedFrom = assignedFrom;
            this.value = value;
        }


        @Override
        protected Object intercept(Map<Method, Interceptor> interceptors, Object[] args, Callable<Object> superCall) throws Exception {
            return value;
        }

        private transient String toStringValue = null;

        @Override
        @IgnoreForBinding
        public String toString() {
            if (toStringValue == null) {
                final StringBuilder sb = new StringBuilder(method.getName());
                sb.append("(): ");
                sb.append(assignedFrom);
                sb.append(", ");
                if (value != null) {
                    sb.append(value);
                    sb.append(", class: ");
                    sb.append(value.getClass().getName());
                } else {
                    sb.append("<null>");
                }
                toStringValue = sb.toString();
            }

            return toStringValue;
        }
    }


    public static final class ConfigMagicParametrizedValue extends Interceptor {
        private final Method method;
        private final ConfigSource config;
        private final String[] properties;
        private final Bully bully;
        private final Object defaultValue;
        private final List<String> paramTokenList;

        private ConfigMagicParametrizedValue(final Method method,
                                             final ConfigSource config,
                                             final String[] properties,
                                             final List<String> paramTokenList,
                                             final Bully bully,
                                             final Object defaultValue) {
            this.method = method;
            this.config = config;
            this.properties = properties;
            this.paramTokenList = paramTokenList;
            this.bully = bully;
            this.defaultValue = defaultValue;
        }

        @Override
        protected Object intercept(Map<Method, Interceptor> interceptors, Object[] args, Callable<Object> superCall) {
            for (String property : properties) {
                if (args.length == paramTokenList.size()) {
                    for (int i = 0; i < args.length; ++i) {
                        property = property.replace(paramTokenList.get(i), String.valueOf(args[i]));
                    }
                    String value = config.getString(property);
                    if (value != null) {
                        logger.debug("Assigning value [{}] for [{}] on [{}#{}()]",
                                new Object[]{value, property, method.getDeclaringClass().getName(), method.getName()});
                        return bully.coerce(method.getGenericReturnType(), value, method.getAnnotation(Separator.class));
                    }
                } else {
                    throw new IllegalStateException("Argument list doesn't match @Param list");
                }
            }
            logger.debug("Assigning default value [{}] for {} on [{}#{}()]",
                    new Object[]{defaultValue, properties, method.getDeclaringClass().getName(), method.getName()});
            return defaultValue;
        }

        private transient String toStringValue = null;

        @Override
        @IgnoreForBinding
        public String toString() {
            if (toStringValue == null) {
                toStringValue = method.getName() + ": " + super.toString();
            }

            return toStringValue;
        }
    }


    public static final class ConfigMagicBeanToString extends Interceptor {

        private transient String toStringValue = null;


        @Override
        protected Object intercept(Map<Method, Interceptor> interceptors, Object[] args, Callable<Object> superCall) {
            Collection<Interceptor> callbacks = interceptors.values();
            if (toStringValue == null) {
                final StringBuilder sb = new StringBuilder();
                Iterator<Interceptor> it = callbacks.iterator();
                while (it.hasNext()) {
                    Object cb = it.next();
                    if (cb != this) {
                        sb.append(cb.toString());

                        if (it.hasNext()) {
                            sb.append("\n");
                        }
                    }
                }


                toStringValue = sb.toString();
            }

            return toStringValue;
        }
    }
}
