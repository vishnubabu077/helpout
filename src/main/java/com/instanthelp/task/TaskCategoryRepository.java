package com.instanthelp.task;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCategoryRepository extends JpaRepository<TaskCategory, String> {
    List<TaskCategory> findByActiveTrueOrderByNameAsc();
}
