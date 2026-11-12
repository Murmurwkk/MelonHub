package com.melonhub.rbac.dao;

import java.util.Collection;

/**
 * What a console operator may see: the applications and departments it is limited to, each null when it is not
 * limited. Lists and reports are filtered by both; an empty collection means nothing is visible.
 */
public record Visibility(Collection<Long> applications, Collection<Long> departments) {
    public static final Visibility ALL = new Visibility(null, null);

    public boolean application(long id) { return applications == null || applications.contains(id); }
    public boolean department(Long id) { return departments == null || id != null && departments.contains(id); }
}
