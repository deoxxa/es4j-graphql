/**
 * Copyright 2016 Eventsourcing team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 */
package com.eventsourcing.graphql;

import com.eventsourcing.Command;
import com.eventsourcing.layout.Layout;
import com.eventsourcing.layout.LayoutIgnore;
import com.eventsourcing.layout.Property;
import graphql.annotations.GraphQLAnnotations;
import graphql.annotations.GraphQLField;
import graphql.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInputObjectField.newInputObjectField;

@Slf4j
public abstract class GraphQLCommand<R> extends Command<R> {

    private final Class<R> resultClass;
    private final Layout<? extends GraphQLCommand> layout;

    @SneakyThrows
    public GraphQLCommand(Class<R> resultClass) {
        this.resultClass = resultClass;
        layout = new Layout<>(getClass());
    }

    private GraphQLFieldDefinition mutation;

    @Setter @Getter (onMethod = @__(@LayoutIgnore)) @Accessors(fluent = true)
    private DataFetchingEnvironment environment;

    @SneakyThrows @SuppressWarnings("unchecked")
    public R mutate(DataFetchingEnvironment environment) {
        GraphQLCommand instance = getClass().newInstance();
        instance.environment(environment);

        Map<String, Object> input = (Map<String, Object>) environment.getArguments().values().toArray()[0];

        instance.setClientMutationId((String) input.get("clientMutationId"));

        for (Property property : layout.getProperties()) {
            Object value = input.get(property.getName());
            property.set(instance, property.getType().isInstanceOf(Optional.class) ? Optional.of(value) : value);
        }

        GraphQLContext context = (GraphQLContext) environment.getContext();
        context.setCommand(instance);

        instance.beforePublishing();
        CompletableFuture<R> future = context.getRepository().publish(instance);
        return future.get();
    }

    protected void beforePublishing() {};

    public static class Mutation extends GraphQLInputObjectType {
        public Mutation(GraphQLObjectType objectType) {
            super(objectType.getName() + "Input", objectType.getDescription(), fields(objectType));
        }

        private static List<GraphQLInputObjectField> fields(GraphQLObjectType objectType) {
            List<GraphQLInputObjectField> fields = new ArrayList<>();
            for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                GraphQLInputObjectField inputField = newInputObjectField().
                        name(field.getName()).
                        description(field.getDescription()).
                        type(field.getType() instanceof GraphQLObjectType ? GraphQLAnnotations.inputObject((GraphQLObjectType) field.getType()) : (GraphQLInputType) field.getType()).
                        build();
                fields.add(inputField);
            }
            return fields;
        }
    }

    @SneakyThrows @LayoutIgnore @SuppressWarnings("unused")
    public GraphQLFieldDefinition getMutation() {
        if (mutation == null) {
            GraphQLObjectType objectType = GraphQLAnnotations.object(this.getClass());
            GraphQLObjectType resultType = GraphQLAnnotations.objectBuilder(resultClass).
                    name(getClass().getSimpleName()).
                    field(newFieldDefinition().name("clientMutationId").type(GraphQLString).dataFetcher(e -> ((GraphQLCommand)((GraphQLContext)e.getContext()).getCommand()).getClientMutationId()).build()).build();

            GraphQLFieldDefinition.Builder builder = newFieldDefinition().
                    name(objectType.getName()).
                    type(resultType).
                    argument(newArgument().name("input").type(new Mutation(objectType)).build()).
                    dataFetcher(this::mutate);

            mutation = builder.build();
        }
        return mutation;
    }

    @Getter(onMethod = @__({@GraphQLField, @LayoutIgnore})) @Setter
    private String clientMutationId;
}
