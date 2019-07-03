package com.mantledillusion.cache.hydnora.exception;

import com.mantledillusion.cache.hydnora.HydnoraCache;

/**
 * {@link RuntimeException} that might by thrown by an
 * {@link HydnoraCache} if any non-{@link RuntimeException} is thrown
 * during loading an entry and no fallback was specified for that case.
 */
public final class EntryLoadingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public EntryLoadingException(Object id, Exception cause) {
		super("Unable to load entry for entry id with '" + id + "': " + cause.getMessage(), cause);
	}
}