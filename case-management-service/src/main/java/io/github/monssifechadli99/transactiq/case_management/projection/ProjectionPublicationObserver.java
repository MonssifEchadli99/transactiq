package io.github.monssifechadli99.transactiq.case_management.projection;

/** Test seam around irreversible publication boundaries; production uses {@link #NONE}. */
public interface ProjectionPublicationObserver {
    ProjectionPublicationObserver NONE = new ProjectionPublicationObserver() {};
    default void afterOwnershipAcquired(ProjectionPartitionOwner owner) {}
    default void afterProducerInitialized(ProjectionPartitionOwner owner) {}
    default void beforeSend(ProjectionPartitionOwner owner, ClaimedProjectionEvent event) {}
    default void beforeCommit(ProjectionPartitionOwner owner, ClaimedProjectionEvent event) {}
    default void afterCommitBeforeMark(ProjectionPartitionOwner owner, ClaimedProjectionEvent event) {}
}
