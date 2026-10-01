package com.instanthelp.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "task_category")
public class TaskCategory {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean active;

    protected TaskCategory() {}

    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
