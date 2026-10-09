package com.hrms.employee.management.utility;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class AuthorizationUtil {

    private AuthorizationUtil() {
    }

    /**
     * Returns the Authorization header of the HTTP request being served on the current thread,
     * exactly as received (including the "Bearer " prefix).
     *
     * @return the Authorization header value, or null when there is no current request (e.g. a
     *         scheduler or async thread) or the request carries no Authorization header
     */
    public static String getAuthorizationHeader() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return null;
        }
        String authorization = ((ServletRequestAttributes) attributes).getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        return authorization == null || authorization.isBlank() ? null : authorization;
    }

    /**
     * Copies the current request's Authorization header onto the headers of an upstream call.
     * Does nothing when there is no Authorization header to forward.
     */
    public static void forwardAuthorization(HttpHeaders headers) {
        String authorization = getAuthorizationHeader();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }
}
