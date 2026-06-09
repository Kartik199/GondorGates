package com.gondorgates.limiter.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@Schema(description = "A rate-limit policy for a URL path prefix")
public class RateLimitPolicy {

    @NotBlank(message = "path must not be blank")
    @Schema(description = "URL path this policy applies to. Matched as a prefix (longest wins).",
            example = "/api/login")
    private String path;

    @NotEmpty(message = "at least one dimension is required")
    @Valid
    @Schema(description = "One or more rate-limit dimensions enforced in declaration order. " +
                          "Evaluation short-circuits on the first denial.")
    private List<DimensionPolicy> dimensions;

    public RateLimitPolicy() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<DimensionPolicy> getDimensions() { return dimensions; }
    public void setDimensions(List<DimensionPolicy> dimensions) { this.dimensions = dimensions; }
}
