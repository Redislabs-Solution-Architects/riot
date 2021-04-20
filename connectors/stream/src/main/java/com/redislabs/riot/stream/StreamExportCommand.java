package com.redislabs.riot.stream;

import com.redislabs.riot.AbstractFlushingTransferCommand;
import com.redislabs.riot.StepBuilder;
import com.redislabs.riot.stream.kafka.KafkaItemWriter;
import com.redislabs.riot.stream.processor.AvroProducerProcessor;
import com.redislabs.riot.stream.processor.JsonProducerProcessor;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.redis.RedisClusterStreamItemReader;
import org.springframework.batch.item.redis.RedisStreamItemReader;
import org.springframework.batch.item.redis.support.StreamItemReader;
import org.springframework.core.convert.converter.Converter;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Command(name = "export", description = "Import Redis streams into Kafka topics")
public class StreamExportCommand extends AbstractFlushingTransferCommand {

    @SuppressWarnings("unused")
    @Parameters(arity = "0..*", description = "One ore more streams to read from", paramLabel = "STREAM")
    private String[] streams;
    @CommandLine.Mixin
    private KafkaOptions options = KafkaOptions.builder().build();
    @Option(names = "--offset", description = "XREAD offset (default: ${DEFAULT-VALUE})", paramLabel = "<string>")
    private String offset = "0-0";
    @SuppressWarnings("unused")
    @Option(names = "--topic", description = "Target topic key (default: same as stream)", paramLabel = "<string>")
    private String topic;

    @Override
    protected Flow flow() {
        Assert.isTrue(!ObjectUtils.isEmpty(streams), "No stream specified");
        List<Step> steps = new ArrayList<>();
        for (String stream : streams) {
            StreamItemReader<String, String, ?> reader = reader(StreamOffset.from(stream, offset));
            StepBuilder<StreamMessage<String, String>, ProducerRecord<String, Object>> step = stepBuilder(stream + "-stream-export-step", "Exporting from " + stream);
            steps.add(configure(step.reader(reader).processor(processor()).writer(writer()).build()).build());
        }
        return flow(steps.toArray(new Step[0]));
    }

    private StreamItemReader<String, String, ?> reader(StreamOffset<String> offset) {
        if (connection instanceof StatefulRedisClusterConnection) {
            log.info("Creating cluster stream reader with offset {}", offset);
            return RedisClusterStreamItemReader.builder((StatefulRedisClusterConnection<String, String>) connection).offset(offset).build();
        }
        log.info("Creating stream reader with offset {}", offset);
        return RedisStreamItemReader.builder((StatefulRedisConnection<String, String>) connection).offset(offset).build();
    }

    private KafkaItemWriter<String> writer() {
        Map<String, Object> producerProperties = options.producerProperties();
        log.info("Creating Kafka writer with producer properties {}", producerProperties);
        return KafkaItemWriter.<String>builder().kafkaTemplate(new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProperties))).build();
    }

    private ItemProcessor<StreamMessage<String, String>, ProducerRecord<String, Object>> processor() {
        switch (options.getSerde()) {
            case JSON:
                return new JsonProducerProcessor(topicConverter());
            default:
                return new AvroProducerProcessor(topicConverter());
        }
    }

    private Converter<StreamMessage<String, String>, String> topicConverter() {
        if (topic == null) {
            return StreamMessage::getStream;
        }
        return s -> topic;
    }

}
