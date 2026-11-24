package com.sbada.graphql;

import java.util.Map;

import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;

/** GraphQL 오류도 REST 와 같은 코드 · 한국어 메시지로: errors[0].message, errors[0].extensions.code */
@Component
public class GraphqlErrors extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ApiException e) {
            ErrorType type = switch (e.getStatus()) {
                case 401 -> ErrorType.UNAUTHORIZED;
                case 403 -> ErrorType.FORBIDDEN;
                case 404 -> ErrorType.NOT_FOUND;
                default -> ErrorType.BAD_REQUEST;
            };
            return GraphqlErrorBuilder.newError(env).errorType(type).message(e.getMessage())
                    .extensions(Map.of("code", e.getCode())).build();
        }
        return null;
    }
}
