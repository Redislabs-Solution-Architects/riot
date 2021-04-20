package com.redislabs.riot.stream;

import com.redislabs.riot.AbstractFlushingTransferCommand;
import com.redislabs.riot.StepBuilder;
import com.redislabs.riot.redis.FilteringOptions;
import com.redislabs.riot.stream.kafka.KafkaItemReader;
import com.redislabs.riot.stream.kafka.KafkaItemReaderBuilder;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.redis.RedisClusterOperationItemWriter;
import org.springframework.batch.item.redis.RedisOperationItemWriter;
import org.springframework.batch.item.redis.support.RedisOperation;
import org.springframework.batch.item.redis.support.RedisOperationBuilder;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Command(name = "import", description = "Import Kafka topics into Redis streams")
public class StreamImportCommand extends AbstractFlushingTransferCommand {

    @SuppressWarnings("unused")
    @Parameters(arity = "0..*", description = "One ore more topics to read from", paramLabel = "TOPIC")
    private String[] topics;
    @CommandLine.Mixin
    private KafkaOptions options = KafkaOptions.builder().build();
    @SuppressWarnings("unused")
    @Option(names = "--key", description = "Target stream key (default: same as topic)", paramLabel = "<string>")
    private String key;
    @SuppressWarnings("unused")
    @Option(names = "--maxlen", description = "Stream maxlen", paramLabel = "<int>")
    private Long maxlen;
    @SuppressWarnings("unused")
    @Option(names = "--trim", description = "Stream efficient trimming ('~' flag)")
    private boolean approximateTrimming;
    @CommandLine.Mixin
    private FilteringOptions filteringOptions = FilteringOptions.builder().build();

    @Override
    protected Flow flow() {
        Assert.isTrue(!ObjectUtils.isEmpty(topics), "No topic specified");
        List<Step> steps = new ArrayList<>();
        Properties consumerProperties = options.consumerProperties();
        log.info("Using Kafka consumer properties: {}", consumerProperties);
        for (String topic : topics) {
            log.info("Creating Kafka reader for topic {}", topic);
            KafkaItemReader<String, Object> reader = new KafkaItemReaderBuilder<String, Object>().partitions(0).consumerProperties(consumerProperties).partitions(0).name(topic).saveState(false).topic(topic).build();
            StepBuilder<ConsumerRecord<String, Object>, ConsumerRecord<String, Object>> step = stepBuilder(topic + "-stream-import-step", "Importing from " + topic);
            steps.add(configure(step.reader(reader).writer(writer()).build()).build());
        }
        return flow(steps.toArray(new Step[0]));
    }

    @SuppressWarnings("unchecked")
    private ItemWriter<ConsumerRecord<String, Object>> writer() {
        XAddArgs xAddArgs = xAddArgs();
        RedisOperation<String, String, ConsumerRecord<String, Object>> operation = RedisOperationBuilder.<String, String, ConsumerRecord<String, Object>>xadd().keyConverter(keyConverter()).argsConverter(r -> xAddArgs).bodyConverter(bodyConverter()).build();
        if (isCluster()) {
            log.info("Creating cluster stream writer");
            return new RedisClusterOperationItemWriter<>((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) pool, operation);
        }
        log.info("Creating stream writer");
        return new RedisOperationItemWriter<>((GenericObjectPool<StatefulRedisConnection<String, String>>) pool, operation);
    }

    private Converter<ConsumerRecord<String, Object>, Map<String, String>> bodyConverter() {
        if (options.getSerde() == KafkaOptions.SerDe.JSON) {
            return new JsonToMapConverter(filteringOptions.converter());
        }
        return new AvroToMapConverter(filteringOptions.converter());
    }

    private Converter<ConsumerRecord<String, Object>, String> keyConverter() {
        if (key == null) {
            return ConsumerRecord::topic;
        }
        return s -> key;
    }

    private XAddArgs xAddArgs() {
        if (maxlen == null) {
            return null;
        }
        XAddArgs args = new XAddArgs();
        args.maxlen(maxlen);
        args.approximateTrimming(approximateTrimming);
        return args;
    }

}
