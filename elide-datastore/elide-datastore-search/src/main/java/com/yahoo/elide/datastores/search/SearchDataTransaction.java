/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */

package com.yahoo.elide.datastores.search;

import com.yahoo.elide.core.DataStoreTransaction;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.datastore.wrapped.WrappedTransaction;
import com.yahoo.elide.core.exceptions.InvalidPredicateException;
import com.yahoo.elide.core.filter.FilterPredicate;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.expression.PredicateExtractionVisitor;
import com.yahoo.elide.core.pagination.Pagination;
import com.yahoo.elide.core.sort.Sorting;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.Fields;
import org.hibernate.search.annotations.Index;
import org.hibernate.search.annotations.SortableField;
import org.hibernate.search.engine.ProjectionConstants;
import org.hibernate.search.jpa.FullTextEntityManager;
import org.hibernate.search.jpa.FullTextQuery;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.hibernate.search.query.dsl.sort.SortFieldContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Performs full text search when it can.  Otherwise delegates to a wrapped transaction.
 */
public class SearchDataTransaction extends WrappedTransaction {

    EntityDictionary dictionary;
    FullTextEntityManager em;

    public SearchDataTransaction(DataStoreTransaction tx, EntityDictionary dictionary, FullTextEntityManager em) {
        super(tx);
        this.dictionary = dictionary;
        this.em = em;
    }

    @Override
    public Iterable<Object> loadObjects(Class<?> entityClass,
                                        Optional<FilterExpression> filterExpression,
                                        Optional<Sorting> sorting,
                                        Optional<Pagination> pagination,
                                        RequestScope requestScope) {

        if (! filterExpression.isPresent()) {
            return super.loadObjects(entityClass, filterExpression, sorting, pagination, requestScope);
        }

        boolean canSearch = canSearch(filterExpression.get(), entityClass);

        if (sorting.isPresent()) {
            canSearch = canSearch && canSort(sorting.get(), entityClass);
        }

        if (canSearch) {
            Query query;
            try {
                query = filterExpression.get().accept(new FilterExpressionToLuceneQuery(em, entityClass));
            } catch (IllegalArgumentException e) {
                throw new InvalidPredicateException(e.getMessage());
            }

            FullTextQuery fullTextQuery = em.createFullTextQuery(query, entityClass);

            if (sorting.isPresent()) {
                fullTextQuery = fullTextQuery.setSort(buildSort(sorting.get(), entityClass));
            }

            if (pagination.isPresent()) {
                fullTextQuery = fullTextQuery.setMaxResults(pagination.get().getLimit());
                fullTextQuery = fullTextQuery.setFirstResult(pagination.get().getOffset());
            }

            List<Object[]> results = fullTextQuery
                    .setProjection(ProjectionConstants.THIS)
                    .getResultList();

            if (pagination.isPresent() && pagination.get().isGenerateTotals()) {
                pagination.get().setPageTotals(fullTextQuery.getResultSize());
            }

            if (results.isEmpty()) {
                return new ArrayList<>();
            }

            return results.stream()
                    .map((result) -> {
                        return result[0];
                    }).collect(Collectors.toList());
        }

        return super.loadObjects(entityClass, filterExpression, sorting, pagination, requestScope);
    }

    /**
     * Returns whether or not Lucene can be used to sort the query.
     * @param sorting The elide sorting clause
     * @param entityClass The entity being sorted.
     * @return true if Lucene can sort.  False otherwise.
     */
    private boolean canSort(Sorting sorting, Class<?> entityClass) {

        boolean canSearch = true;
        for (Map.Entry<Path, Sorting.SortOrder> entry
                : sorting.getValidSortingRules(entityClass, dictionary).entrySet()) {

            Path path = entry.getKey();

            if (path.getPathElements().size() != 1) {
                return false;
            }

            Path.PathElement last = path.lastElement().get();
            String fieldName = last.getFieldName();

            if (dictionary.getAttributeOrRelationAnnotation(entityClass, SortableField.class, fieldName) == null) {
                return false;
            }
        }

        return true;
    }

    /**
     * Verifies whether or not a filter expression can be searched with Lucene.
     * @param expression An elide filter expression.
     * @param entityClass The entity being searched.
     * @return true if Lucene can be used.  False otherwise.
     */
    private boolean canSearch(FilterExpression expression, Class<?> entityClass) {
        Collection<FilterPredicate> predicates = expression.accept(new PredicateExtractionVisitor());

        return predicates.stream().allMatch((predicate) -> {
            if (predicate.getPath().getPathElements().size() != 1) {
                return false;
            }

            String fieldName = predicate.getField();

            List<Field> fields = new ArrayList<>();

            Field fieldAnnotation = dictionary.getAttributeOrRelationAnnotation(entityClass, Field.class, fieldName);

            if (fieldAnnotation != null) {
                fields.add(fieldAnnotation);
            } else {
                Fields fieldsAnnotation =
                        dictionary.getAttributeOrRelationAnnotation(entityClass, Fields.class, fieldName);

                if (fieldsAnnotation != null) {
                    Arrays.stream(fieldsAnnotation.value()).forEach(fields::add);
                }
            }

            boolean indexed = false;

            for (Field field : fields) {
                if (field.index() == Index.YES) {
                    indexed = true;
                }
            }

            return indexed;
        });
    }

    /**
     * Builds a lucene Sort object from and Elide Sorting object.
     * @param sorting Elide sorting object
     * @param entityClass The entity being sorted.
     * @return A lucene Sort object
     */
    private Sort buildSort(Sorting sorting, Class<?> entityClass) {
        QueryBuilder builder = em.getSearchFactory().buildQueryBuilder().forEntity(entityClass).get();

        SortFieldContext context = null;
        for (Map.Entry<Path, Sorting.SortOrder> entry
                : sorting.getValidSortingRules(entityClass, dictionary).entrySet()) {

            String fieldName = entry.getKey().lastElement().get().getFieldName();

            SortableField sortableField =
                    dictionary.getAttributeOrRelationAnnotation(entityClass, SortableField.class, fieldName);

            fieldName = sortableField.forField().isEmpty() ? fieldName : sortableField.forField();

            if (context == null) {
                context = builder.sort().byField(fieldName);
            } else {
                context.andByField(fieldName);
            }

            Sorting.SortOrder order = entry.getValue();

            if (order == Sorting.SortOrder.asc) {
                context = context.asc();
            } else {
                context = context.desc();
            }
        }

        return context.createSort();
    }

    @Override
    public FeatureSupport supportsFiltering(Class<?> entityClass, FilterExpression expression) {
        return FeatureSupport.PARTIAL;
    }
}
