package org.skife.config;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Date;

import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Test;

public class TestDefaultCoercibles
{
    @Test
    public void testValueOfCoercible1()
    {
        Coercer<?> c = DefaultCoercibles.VALUE_OF_COERCIBLE.accept(Date.class);

        Assert.assertThat(c, is(notNullValue()));

        Object result = c.coerce("2010-11-21");

        Assert.assertEquals(Date.class, result.getClass());
        Assert.assertThat((Date) result, equalTo(Date.valueOf("2010-11-21")));
    }

    @Test
    public void testValueOfCoercible2()
    {
        Coercer<?> c = DefaultCoercibles.VALUE_OF_COERCIBLE.accept(Long.class);

        Assert.assertThat(c, is(notNullValue()));

        Object result = c.coerce("4815162342");

        Assert.assertEquals(Long.class, result.getClass());
        Assert.assertThat((Long) result, is(4815162342L));
    }

    @Test
    public void testStringCtor1() throws MalformedURLException
    {
        Coercer<?> c = DefaultCoercibles.STRING_CTOR_COERCIBLE.accept(URL.class);

        Assert.assertThat(c, is(notNullValue()));

        Object result = c.coerce("http://www.cnn.com/");

        Assert.assertEquals(URL.class, result.getClass());
        Assert.assertThat((URL) result, equalTo(new URL("http://www.cnn.com/")));
    }

    @Test
    public void testStringCtor2()
    {
        Coercer<?> c = DefaultCoercibles.STRING_CTOR_COERCIBLE.accept(StringBuilder.class);

        Assert.assertThat(c, is(notNullValue()));

        Object result = c.coerce("Ich bin zwei Oeltanks.");

        Assert.assertEquals(StringBuilder.class, result.getClass());
        Assert.assertThat(result.toString(), is("Ich bin zwei Oeltanks."));
    }

    @Test
    public void testObjectCtor1()
    {
        Coercer<?> c = DefaultCoercibles.OBJECT_CTOR_COERCIBLE.accept(DateTime.class);

        Assert.assertThat(c, is(notNullValue()));

        Object result = c.coerce("2010-11-22T01:58Z");

        Assert.assertEquals(DateTime.class, result.getClass());
        Assert.assertThat((DateTime) result, equalTo(new DateTime("2010-11-22T01:58Z")));
    }
}
