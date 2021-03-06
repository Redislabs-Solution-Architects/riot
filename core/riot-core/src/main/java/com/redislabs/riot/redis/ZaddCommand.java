package com.redislabs.riot.redis;

import org.springframework.batch.item.redis.support.operation.Zadd;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;

@Command(name = "zadd", description = "Add members with scores to a sorted set")
public class ZaddCommand extends AbstractCollectionCommand {

    @SuppressWarnings("unused")
    @Option(names = "--score", description = "Name of the field to use for scores", paramLabel = "<field>")
    private String scoreField;
    @Option(names = "--score-default", description = "Score when field not present (default: ${DEFAULT-VALUE})", paramLabel = "<num>")
    private double scoreDefault = 1;

    @Override
    public Zadd<Map<String, Object>> operation() {
        return new Zadd<>(key(), member(), numberFieldExtractor(Double.class, scoreField, scoreDefault));
    }

}
