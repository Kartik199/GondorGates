package com.gondorgates.limiter.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class RateLimitPolicy {
    @NotBlank(message = "path must not be blank")
    private String path;
    @NotEmpty(message = "at least one dimension is required")
    @Valid
    private List<DimensionPolicy> dimensions;

    public RateLimitPolicy() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<DimensionPolicy> getDimensions() { return dimensions; }
    public void setDimensions(List<DimensionPolicy> dimensions) { this.dimensions = dimensions; }
}