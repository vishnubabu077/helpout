package com.instanthelp.task;

import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/categories")
@Tag(name = "Tasks")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CategoryController {

    private final TaskService service;

    public CategoryController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List supported task categories")
    public List<CategoryResponse> list() {
        return service.categories();
    }
}
