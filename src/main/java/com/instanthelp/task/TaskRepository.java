package com.instanthelp.task;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    Optional<Task> findByIdAndRequesterId(UUID id, UUID requesterId);

    Page<Task> findByRequesterId(UUID requesterId, Pageable pageable);
}
