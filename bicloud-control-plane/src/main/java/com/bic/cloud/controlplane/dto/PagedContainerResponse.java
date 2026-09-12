package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedContainerResponse {

    private List<ContainerInstanceDetailResponse> content;

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    private Map<String, Long> statusCounts;
}
