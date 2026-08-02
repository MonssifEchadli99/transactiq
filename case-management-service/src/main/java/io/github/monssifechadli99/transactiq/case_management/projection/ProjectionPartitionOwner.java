package io.github.monssifechadli99.transactiq.case_management.projection;

import java.util.UUID;

public record ProjectionPartitionOwner(String topic, int partition, UUID token, long generation) {}
