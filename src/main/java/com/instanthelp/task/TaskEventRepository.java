package com.instanthelp.task;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, UUID> {}
