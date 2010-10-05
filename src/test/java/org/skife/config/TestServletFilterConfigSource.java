package org.skife.config;

import static org.hamcrest.CoreMatchers.is;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;

import org.apache.commons.collections.iterators.IteratorEnumeration;
import org.junit.Assert;
import org.junit.Test;

public class TestServletFilterConfigSource
{
    @Test
    public void simpleTest()
    {
        final MockFilterConfig mfc = new MockFilterConfig();
        mfc.put("foo", "hello, world");
        mfc.put("bar", "23");

        final ServletFilterConfigSource sfcs = new ServletFilterConfigSource(mfc);

        final ConfigurationObjectFactory configurationObjectFactory = new ConfigurationObjectFactory(sfcs);

        final Config5 config = configurationObjectFactory.build(Config5.class);

        Assert.assertThat(config.getFoo(), is("hello, world"));
        Assert.assertThat(config.getBar(), is(23));
    }

    private static class MockFilterConfig implements FilterConfig
    {
        private final Map<String, String> parameters = new HashMap<String, String>();

        private void put(String key, String value)
        {
            parameters.put(key, value);
        }

        public String getFilterName() {
            return "bogus";
        }

        public String getInitParameter(String name) {
            return  parameters.get(name);
        }

        @SuppressWarnings("unchecked")
        public Enumeration getInitParameterNames() {
            return new IteratorEnumeration(parameters.keySet().iterator());
        }

        public ServletContext getServletContext() {
            return null;
        }

    }
}


