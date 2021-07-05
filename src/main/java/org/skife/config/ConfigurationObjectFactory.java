package org.skife.config;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurationObjectFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationObjectFactory.class);
    private final ConfigSource config;
    private final Bully bully;

    public ConfigurationObjectFactory(Properties props) {
        this(new SimplePropertyConfigSource(props));
    }

    public ConfigurationObjectFactory(ConfigSource config) {
        this.config = config;
        this.bully = new Bully();
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

    private <T> T internalBuild(Class<T> configClass, Map<String, String> mappedReplacements) {
        final Map<Method, Object> interceptors = new HashMap<Method, Object>();

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

        // Hook up a toString method that prints out the settings for that bean if possible.
        final Method toStringMethod = findToStringMethod(configClass);
        if (toStringMethod != null) {
            List<Object> callbacks = new ArrayList<Object>(interceptors.values());
            interceptors.put(toStringMethod, new ConfigMagicBeanToString(callbacks));
        }


        DynamicType.Builder<T> builder = new ByteBuddy().subclass(configClass);
        for (Map.Entry<Method, Object> e : interceptors.entrySet()) {
            Object cb = e.getValue();
            if (cb != null) {
                builder = builder
                        .method(ElementMatchers.is(e.getKey()))
                        .intercept(MethodDelegation.to(cb));
            }
        }
        try {
            return builder
                    .make()
                    .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded()
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object buildSimple(Method method, Config annotation,
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
                    logger.info("Assigning value [{}] for [{}] on [{}#{}()]",
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
                logger.info("Assigning mappedReplacement value [{}] for [{}] on [{}#{}()]",
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

                logger.info("Assigning default value [{}] for {} on [{}#{}()]",
                        new Object[]{value, propertyNames, method.getDeclaringClass().getName(), method.getName()});
            } else if (hasDefaultNull) {
                logger.info("Assigning null default value for {} on [{}#{}()]",
                        new Object[]{propertyNames, method.getDeclaringClass().getName(), method.getName()});
                assignedFrom = "annotation: @DefaultNull";
            } else {
                // Final try: Is the method is actually callable?
                if (!Modifier.isAbstract(method.getModifiers())) {
                    assignedFrom = "method: '" + method.getName() + "()'";
                    logger.info("Using method itself for {} on [{}#{}()]",
                            new Object[]{propertyNames, method.getDeclaringClass().getName(), method.getName()});
                    return new ConfigMagicSuperValue(method, assignedFrom);
                } else {
                    throw new IllegalArgumentException(String.format("No value present for '%s' in [%s]",
                            prettyPrint(propertyNames, mappedReplacements),
                            method.toGenericString()));
                }
            }
        }

        final Object finalValue = bully.coerce(method.getGenericReturnType(), value, method.getAnnotation(Separator.class));
        return new ConfigMagicFixedValue(method, assignedFrom, finalValue);
    }

    private String applyReplacements(String propertyName, Map<String, String> mappedReplacements) {
        for (String key : mappedReplacements.keySet()) {
            String token = makeToken(key);
            String replacement = mappedReplacements.get(key);
            propertyName = propertyName.replace(token, replacement);
        }
        return propertyName;
    }

    private Object buildParameterized(Method method, Config annotation) {
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

        return new ConfigMagicMethodInterceptor(method,
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

    public static final class ConfigMagicSuperValue {
        private final Method method;
        private final String assignedFrom;


        private ConfigMagicSuperValue(final Method method, final String assignedFrom) {
            this.method = method;
            this.assignedFrom = assignedFrom;
        }

        @RuntimeType
        public Object intercept(@SuperCall Callable<?> zuper) throws Exception {
            return zuper.call();
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


    public static final class ConfigMagicFixedValue {
        private final Method method;
        private final String assignedFrom;
        private final Object value;


        private ConfigMagicFixedValue(final Method method, final String assignedFrom, final Object value) {
            this.method = method;
            this.assignedFrom = assignedFrom;
            this.value = value;
        }

        @RuntimeType
        public Object intercept() {
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


    public static final class ConfigMagicMethodInterceptor {
        private final Method method;
        private final ConfigSource config;
        private final String[] properties;
        private final Bully bully;
        private final Object defaultValue;
        private final List<String> paramTokenList;

        private ConfigMagicMethodInterceptor(final Method method,
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


        @RuntimeType
        public Object intercept(@AllArguments Object[] args,
                                @Origin Method method) {
            for (String property : properties) {
                if (args.length == paramTokenList.size()) {
                    for (int i = 0; i < args.length; ++i) {
                        property = property.replace(paramTokenList.get(i), String.valueOf(args[i]));
                    }
                    String value = config.getString(property);
                    if (value != null) {
                        logger.info("Assigning value [{}] for [{}] on [{}#{}()]",
                                new Object[]{value, property, method.getDeclaringClass().getName(), method.getName()});
                        return bully.coerce(method.getGenericReturnType(), value, method.getAnnotation(Separator.class));
                    }
                } else {
                    throw new IllegalStateException("Argument list doesn't match @Param list");
                }
            }
            logger.info("Assigning default value [{}] for {} on [{}#{}()]",
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

    private Method findToStringMethod(final Class<?> clazz) {
        try {
            return clazz.getMethod("toString", new Class[]{});
        } catch (NoSuchMethodException nsme) {
            try {
                return Object.class.getMethod("toString", new Class[]{});
            } catch (NoSuchMethodException nsme2) {
                throw new IllegalStateException("Could not intercept toString method!", nsme);
            }
        }
    }

    public static final class ConfigMagicBeanToString {
        private final List<Object> callbacks;

        private transient String toStringValue = null;

        private ConfigMagicBeanToString(final List<Object> callbacks) {
            this.callbacks = callbacks;
        }

        @RuntimeType
        public Object intercept() {
            if (toStringValue == null) {
                final StringBuilder sb = new StringBuilder();

                for (int i = 0; i < callbacks.size(); i++) {
                    sb.append(callbacks.get(i).toString());

                    if (i < callbacks.size() - 1) {
                        sb.append("\n");
                    }
                }

                toStringValue = sb.toString();
            }

            return toStringValue;
        }
    }
}
