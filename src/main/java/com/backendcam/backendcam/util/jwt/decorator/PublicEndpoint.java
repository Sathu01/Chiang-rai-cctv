package com.backendcam.backendcam.util.jwt.decorator;

import java.lang.annotation.*;

/**
 * Marks an endpoint as public.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {}