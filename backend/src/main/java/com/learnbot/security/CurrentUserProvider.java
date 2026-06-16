package com.learnbot.security;

import com.learnbot.service.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserProvider {
    public static final String REQUEST_ATTRIBUTE = "learnbot.currentUser";

    public AppUser currentUser() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new UnauthorizedException("Authentication is required.");
        }
        HttpServletRequest request = attributes.getRequest();
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof AppUser user) {
            return user;
        }
        throw new UnauthorizedException("Authentication is required.");
    }
}

