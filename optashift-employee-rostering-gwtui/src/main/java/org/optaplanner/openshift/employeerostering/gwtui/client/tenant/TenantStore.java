/*
 * Copyright (C) 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.openshift.employeerostering.gwtui.client.tenant;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;

import org.optaplanner.openshift.employeerostering.gwtui.client.common.FailureShownRestCallback;
import org.optaplanner.openshift.employeerostering.gwtui.client.tenant.TenantStore.TenantsReady;
import org.optaplanner.openshift.employeerostering.shared.tenant.Tenant;
import org.optaplanner.openshift.employeerostering.shared.tenant.TenantRestServiceBuilder;

@ApplicationScoped
public class TenantStore {

    private List<Tenant> tenantList;

    private Tenant current;

    @Inject
    private Event<TenantChange> tenantChangeEvent;

    @Inject
    private Event<TenantsReady> tenantsReadyEvent;

    // @PostConstruct
    public void init() {
        refresh();
    }

    public void setCurrentTenant(final Tenant newTenant) {
        current = newTenant;
        tenantChangeEvent.fire(new TenantChange());
    }

    public Integer getCurrentTenantId() {
        return current.getId();
    }

    public List<Tenant> getTenantList() {
        return tenantList;
    }

    public void updateTenant(Tenant updatedValue) {
        tenantList.set(tenantList.indexOf(updatedValue), updatedValue);
    }

    public Tenant getCurrentTenant() {
        return current;
    }

    public static class TenantChange {

    }

    public static class TenantsReady {

    }

    public void refresh() {
        TenantRestServiceBuilder.getTenantList(FailureShownRestCallback.onSuccess(tenantList -> {
            this.tenantList = tenantList;
            setCurrentTenant(tenantList.get(0));
            tenantsReadyEvent.fire(new TenantsReady());
        }));
    }
}
