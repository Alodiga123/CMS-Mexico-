package bank.cardissuing.common.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Function;

/**
 * The shape every paged list answers with. Lists stay backward compatible: without
 * {@code page} / {@code size} an endpoint returns the plain array it always did; with
 * them it returns this envelope, so the console can page on the server as data grows.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static final int MAX_SIZE = 500;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }

    /** Null when the caller did not ask for a page (compat mode); otherwise a bounded, zero-based request. */
    public static Pageable pageable(Integer page, Integer size) {
        if (page == null && size == null) return null;
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        return PageRequest.of(p, s);
    }
}
