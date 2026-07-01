package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * Response for server-side paging, filtering and sorting.
 *
 * statusCounts holds totals with the current search/serviceName filter
 * applied but WITHOUT the status filter, so the status chips in the UI can
 * show "how many would I get if I switched to this filter".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedContainerResponse {

    private List<ContainerInstanceDetailResponse> content;

    private int page;            // 0-indexed
    private int size;
    private long totalElements;
    private int totalPages;

    /** RUNNING, FAILED, STOPPED, PENDING -> count. Statuses with no matches return 0. */
    private Map<String, Long> statusCounts;
}
