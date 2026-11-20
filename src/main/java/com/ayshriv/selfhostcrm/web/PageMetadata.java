package com.ayshriv.selfhostcrm.web;

import org.springframework.data.domain.Page;

/** Pagination metadata; field names are identical across all list endpoints. */
public record PageMetadata(int currentPage, int pageSize, int totalPages, long totalItems) {

	public static PageMetadata from(Page<?> page) {
		return new PageMetadata(page.getNumber(), page.getSize(), page.getTotalPages(), page.getTotalElements());
	}

}
