package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateProjectDto {

    @NotBlank(message = "Project name must not be blank.")
    @Size(min = 2, max = 50,
          message = "Project name must be between 2 and 50 characters.")
    @Pattern(
            regexp = "^(?!bicloud-)(?!egress-)[a-z](?:[a-z0-9-]{0,48}[a-z0-9])$",
            message = "Project name may only contain lowercase letters, digits and hyphens (-); " +
                      "it must start with a letter, must not end with a hyphen " +
                      "and must not start with the reserved 'bicloud-' or 'egress-' prefixes."
    )
    private String name;
}
