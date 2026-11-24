package com.contextshield.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Todas as respostas de erro têm o mesmo formato: timestamp, status, error, path e (se houver) fields. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, HttpServletRequest req, Map<String, String> fields) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("timestamp", Instant.now().toString());
        b.put("status", status.value());
        b.put("error", error);
        b.put("path", req.getRequestURI());
        if (fields != null && !fields.isEmpty()) b.put("fields", fields);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest req) {
        return body(e.status(), e.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "Alguns campos precisam de correção", req, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "Requisição em formato inválido", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denied(AccessDeniedException e, HttpServletRequest req) {
        return body(HttpStatus.FORBIDDEN, "Seu perfil não tem acesso a este recurso", req, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> concurrent(Exception e, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "Esta compra foi alterada ao mesmo tempo por outra requisição. Tente de novo.", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> integrity(Exception e, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "O dado viola uma regra de integridade do banco", req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(Exception e, HttpServletRequest req) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "Método não suportado nesta rota", req, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(Exception e, HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "Rota não encontrada", req, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        log.error("Erro inesperado em {}", req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado. Tente novamente.", req, null);
    }
}
