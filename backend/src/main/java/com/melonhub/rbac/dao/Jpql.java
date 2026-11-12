package com.melonhub.rbac.dao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers shared by the DAOs: JPQL conditions built step by step, and result rows shaped like the JDBC rows the
 * services and the JSON contract were written for (column aliases as keys, java.sql.Timestamp for times).
 */
final class Jpql {
    private Jpql() {}

    /** Conditions joined with AND. A "?" in a clause becomes the next named parameter. */
    static final class Where {
        final StringBuilder text = new StringBuilder(" where 1=1");
        final Map<String,Object> params = new LinkedHashMap<>();

        Where and(String clause, Object... values) {
            var out = new StringBuilder();
            int next = 0;
            for (char c : clause.toCharArray()) {
                if (c != '?') { out.append(c); continue; }
                String name = "p" + params.size();
                params.put(name, values[next++]);
                out.append(':').append(name);
            }
            text.append(" and ").append(out);
            return this;
        }
        /** path in (values); no values matches nothing. */
        Where in(String path, Collection<?> values) { return values.isEmpty() ? and("1=0") : and(path + " in ?", values); }
        <T> TypedQuery<T> bind(TypedQuery<T> query) {
            params.forEach(query::setParameter);
            return query;
        }
    }

    static Tuple tuple(TypedQuery<Tuple> query) { return query.getResultStream().findFirst().orElse(null); }
    static Map<String,Object> row(Tuple tuple) {
        var row = new LinkedHashMap<String,Object>();
        for (TupleElement<?> element : tuple.getElements()) {
            Object value = tuple.get(element);
            row.put(element.getAlias(), value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
        return row;
    }
    static List<Map<String,Object>> rows(List<Tuple> tuples) {
        var rows = new ArrayList<Map<String,Object>>(tuples.size());
        for (Tuple tuple : tuples) rows.add(row(tuple));
        return rows;
    }

    /** One page of rows and the total, counted with the same conditions. */
    static RbacDao.Page page(EntityManager em, String select, String from, Where where, String order, int page, int size) {
        long total = where.bind(em.createQuery("select count(*) " + from + where.text, Long.class)).getSingleResult();
        if (total == 0) return new RbacDao.Page(new ArrayList<>(), 0);
        var query = where.bind(em.createQuery(select + " " + from + where.text + " order by " + order, Tuple.class));
        query.setFirstResult((int) Math.min(Integer.MAX_VALUE, (long) (page - 1) * size)).setMaxResults(size);
        return new RbacDao.Page(rows(query.getResultList()), total);
    }

    /** A LIKE pattern matching the keyword anywhere; use it with {@code escape '!'}. */
    static String like(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    /** TIMESTAMP columns hold whole seconds and MySQL rounds fractions, so stored times are truncated first. */
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
    static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
