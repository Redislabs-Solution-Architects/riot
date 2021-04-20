package com.redislabs.riot.gen;

import com.redislabs.mesclun.RedisModulesClient;
import com.redislabs.mesclun.StatefulRedisModulesConnection;
import com.redislabs.mesclun.search.*;
import com.redislabs.riot.RiotIntegrationTest;
import com.redislabs.testcontainers.RedisContainer;
import com.redislabs.testcontainers.RedisModulesContainer;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestGen extends RiotIntegrationTest {

    @Container
    private static final RedisModulesContainer REDIS_MODULES = new RedisModulesContainer();

    @Override
    protected RiotGen app() {
        return new RiotGen();
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void genFakerHash(RedisContainer container) throws Exception {
        execute("import-hset", container);
        RedisKeyCommands<String, String> sync = sync(container);
        List<String> keys = sync.keys("person:*");
        Assertions.assertEquals(1000, keys.size());
        Map<String, String> person = ((RedisHashCommands<String, String>) sync).hgetall(keys.get(0));
        Assertions.assertTrue(person.containsKey("firstName"));
        Assertions.assertTrue(person.containsKey("lastName"));
        Assertions.assertTrue(person.containsKey("address"));
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void genFakerSet(RedisContainer container) throws Exception {
        execute("import-sadd", container);
        RedisSetCommands<String, String> sync = sync(container);
        Set<String> names = sync.smembers("got:characters");
        Assertions.assertTrue(names.size() > 10);
        for (String name : names) {
            Assertions.assertFalse(name.isEmpty());
        }
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void genFakerZset(RedisContainer container) throws Exception {
        execute("import-zadd", container);
        RedisKeyCommands<String, String> sync = sync(container);
        List<String> keys = sync.keys("leases:*");
        Assertions.assertTrue(keys.size() > 100);
        String key = keys.get(0);
        Assertions.assertTrue(((RedisSortedSetCommands<String, String>) sync).zcard(key) > 0);
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void genFakerStream(RedisContainer container) throws Exception {
        execute("import-xadd", container);
        RedisStreamCommands<String, String> sync = sync(container);
        List<StreamMessage<String, String>> messages = sync.xrange("teststream:1", Range.unbounded());
        Assertions.assertTrue(messages.size() > 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void genFakerIndexIntrospection() throws Exception {
        String INDEX = "beerIdx";
        String FIELD_ID = "id";
        String FIELD_ABV = "abv";
        String FIELD_NAME = "name";
        String FIELD_STYLE = "style";
        String FIELD_OUNCES = "ounces";
        RedisModulesClient modulesClient = RedisModulesClient.create(REDIS_MODULES.getRedisURI());
        StatefulRedisModulesConnection<String, String> connection = modulesClient.connect();
        connection.sync().create(INDEX, CreateOptions.<String, String>builder().prefix("beer:").build(), Field.tag(FIELD_ID).sortable(true).build(), Field.text(FIELD_NAME).sortable(true).build(), Field.text(FIELD_STYLE).matcher(Field.Text.PhoneticMatcher.English).sortable(true).build(), Field.numeric(FIELD_ABV).sortable(true).build(), Field.numeric(FIELD_OUNCES).sortable(true).build());
        execute("import-infer", REDIS_MODULES);
        SearchResults<String, String> results = connection.sync().search(INDEX, "*");
        Assertions.assertEquals(1000, results.getCount());
        Document<String, String> doc1 = results.get(0);
        Assertions.assertNotNull(doc1.get(FIELD_ABV));
        connection.close();
        modulesClient.shutdown();
        modulesClient.getResources().shutdown();
    }

}
