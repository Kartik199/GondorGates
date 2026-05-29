package com.gondorgates.limiter_service.policy;

import jakarta.validation.Valid;
import java.util.List;

public class RateLimitPolicy {
    private String path;
    @Valid
    private List<DimensionPolicy> dimensions;

    public RateLimitPolicy() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<DimensionPolicy> getDimensions() { return dimensions; }
    public void setDimensions(List<DimensionPolicy> dimensions) { this.dimensions = dimensions; }
}