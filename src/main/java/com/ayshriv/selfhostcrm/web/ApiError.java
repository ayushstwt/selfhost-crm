package com.ayshriv.selfhostcrm.web;

/** A field-level error, mainly for validation failures. */
public record ApiError(String field, String message) {
}
