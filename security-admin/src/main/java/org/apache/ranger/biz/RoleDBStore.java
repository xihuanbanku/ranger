/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.biz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.common.MessageEnums;
import org.apache.ranger.common.RESTErrorUtil;
import org.apache.ranger.common.RangerRoleCache;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.*;
import org.apache.ranger.plugin.model.RangerRole;
import org.apache.ranger.plugin.store.AbstractPredicateUtil;
import org.apache.ranger.plugin.store.RolePredicateUtil;
import org.apache.ranger.plugin.store.RoleStore;
import org.apache.ranger.plugin.util.RangerRoles;
import org.apache.ranger.plugin.util.SearchFilter;
import org.apache.ranger.service.RangerRoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@Component
public class RoleDBStore implements RoleStore {
    private static final Log LOG = LogFactory.getLog(RoleDBStore.class);

    private static final String RANGER_ROLE_GLOBAL_STATE_NAME = "RangerRole";

    @Autowired
    RangerRoleService roleService;

    @Autowired
    RangerDaoManager daoMgr;

    @Autowired
    RESTErrorUtil restErrorUtil;

    @Autowired
	RoleRefUpdater roleRefUpdater;

    @Autowired
    RangerBizUtil bizUtil;

    private Boolean populateExistingBaseFields = true;

    AbstractPredicateUtil predicateUtil = null;

    public void init() throws Exception {}

    @PostConstruct
    public void initStore() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RoleDBStore.initStore()");
        }

        roleService.setPopulateExistingBaseFields(populateExistingBaseFields);
        predicateUtil = new RolePredicateUtil();

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== RoleDBStore.initStore()");
        }
    }

    @Override
    public RangerRole createRole(RangerRole role) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RoleDBStore.createRole()");
        }

        XXRole xxRole = daoMgr.getXXRole().findByRoleName(role.getName());

        if (xxRole != null) {
            throw restErrorUtil.createRESTException("role with name: " + role.getName() + " already exists", MessageEnums.ERROR_DUPLICATE_OBJECT);
        }

        daoMgr.getXXGlobalState().onGlobalAppDataChange(RANGER_ROLE_GLOBAL_STATE_NAME);

        RangerRole createdRole = roleService.create(role);
        if (createdRole == null) {
            throw new Exception("Cannot create role:[" + role + "]");
        }

        roleRefUpdater.createNewRoleMappingForRefTable(createdRole);

        List<XXTrxLog> trxLogList = roleService.getTransactionLog(createdRole, null, "create");
        bizUtil.createTrxLog(trxLogList);
        return createdRole;
    }

    @Override
    public RangerRole updateRole(RangerRole role) throws Exception {
        XXRole xxRole = daoMgr.getXXRole().findByRoleId(role.getId());
        if (xxRole == null) {
            throw restErrorUtil.createRESTException("role with id: " + role.getId() + " does not exist");
        }

		if (!role.getName().equals(xxRole.getName())) { // ensure only if role name is changed
			ensureRoleNameUpdateAllowed(xxRole.getName());
		}

        Gson gsonBuilder = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSS-Z").create();
        RangerRole oldRole = gsonBuilder.fromJson(xxRole.getRoleText(), RangerRole.class);

        daoMgr.getXXGlobalState().onGlobalAppDataChange(RANGER_ROLE_GLOBAL_STATE_NAME);

        RangerRole updatedRole = roleService.update(role);
        if (updatedRole == null) {
            throw new Exception("Cannot update role:[" + role + "]");
        }

        roleRefUpdater.createNewRoleMappingForRefTable(updatedRole);

        roleService.updatePolicyVersions(updatedRole.getId());

        if (ServiceDBStore.isSupportsRolesDownloadByService()) {
            roleService.updateRoleVersions(updatedRole.getId());
        }

        List<XXTrxLog> trxLogList = roleService.getTransactionLog(updatedRole, oldRole, "update");
        bizUtil.createTrxLog(trxLogList);
        return role;
    }

	private void ensureRoleNameUpdateAllowed(String roleName) throws Exception {
		boolean roleNotInPolicy = ensureRoleNotInPolicy(roleName);
		if (!roleNotInPolicy) {
			throw new Exception(
					"Rolename for '" + roleName + "' can not be updated as it is referenced in one or more policies");
		}

		boolean roleNotInOtherRole = ensureRoleNotInRole(roleName);
		if (!roleNotInOtherRole) {
			throw new Exception("Rolename for '" + roleName
					+ "' can not be updated as it is referenced in one or more other roles");
		}
	}

	@Override
    public void deleteRole(String roleName) throws Exception {
        XXRole xxRole = daoMgr.getXXRole().findByRoleName(roleName);
        if (xxRole == null) {
            throw restErrorUtil.createRESTException("Role with name: " + roleName + " does not exist");
        }

        ensureRoleDeleteAllowed(roleName);

        daoMgr.getXXGlobalState().onGlobalAppDataChange(RANGER_ROLE_GLOBAL_STATE_NAME);

        RangerRole role = roleService.read(xxRole.getId());
        roleRefUpdater.cleanupRefTables(role);
        roleService.delete(role);

        List<XXTrxLog> trxLogList = roleService.getTransactionLog(role, null, "delete");
        bizUtil.createTrxLog(trxLogList);
    }

    @Override
    public void deleteRole(Long roleId) throws Exception {
        RangerRole role = roleService.read(roleId);

        ensureRoleDeleteAllowed(role.getName());

        daoMgr.getXXGlobalState().onGlobalAppDataChange(RANGER_ROLE_GLOBAL_STATE_NAME);

        roleRefUpdater.cleanupRefTables(role);
        roleService.delete(role);
        List<XXTrxLog> trxLogList = roleService.getTransactionLog(role, null, "delete");
        bizUtil.createTrxLog(trxLogList);
    }

    private void ensureRoleDeleteAllowed(String roleName) throws Exception {
        boolean roleNotInPolicy = ensureRoleNotInPolicy(roleName);
        if(!roleNotInPolicy) {
            throw new Exception("Role '"+ roleName +"' can not be deleted as it is referenced in one or more policies");
        }

        boolean roleNotInOtherRole = ensureRoleNotInRole(roleName);
        if(!roleNotInOtherRole) {
            throw new Exception("Role '"+ roleName + "' can not be deleted as it is referenced in one or more other roles");
        }
    }

	private boolean ensureRoleNotInPolicy(String roleName) {
		Long roleRefPolicyCount = daoMgr.getXXPolicyRefRole().findRoleRefPolicyCount(roleName);

		return roleRefPolicyCount < 1;
	}

	private boolean ensureRoleNotInRole(String roleName) {
		Long roleRefRoleCount = daoMgr.getXXRoleRefRole().findRoleRefRoleCount(roleName);

		return roleRefRoleCount < 1;
	}

    @Override
    public RangerRole getRole(Long id) throws Exception {
        return roleService.read(id);
    }

    @Override
    public RangerRole getRole(String name) throws Exception {
        XXRole xxRole = daoMgr.getXXRole().findByRoleName(name);
        if (xxRole == null) {
            throw restErrorUtil.createRESTException("Role with name: " + name + " does not exist");
        }
        return roleService.read(xxRole.getId());
    }

    @Override
    public List<RangerRole> getRoles(SearchFilter filter) throws Exception {
        List<RangerRole> ret = new ArrayList<>();

        List<XXRole> xxRoles = daoMgr.getXXRole().getAll();

        if (CollectionUtils.isNotEmpty(xxRoles)) {
            for (XXRole xxRole : xxRoles) {
                ret.add(roleService.read(xxRole.getId()));
            }

            if (predicateUtil != null && filter != null && !filter.isEmpty()) {
                List<RangerRole> copy = new ArrayList<>(ret);

                predicateUtil.applyFilter(copy, filter);
                ret = copy;
            }
        }

        return ret;
    }

    @Override
    public List<String> getRoleNames(SearchFilter filter) throws Exception {
        return daoMgr.getXXRole().getAllNames();
    }

    @Override
    public RangerRoles getRangerRoles(String serviceName, Long lastKnownRoleVersion) throws Exception {
        RangerRoles ret                   = null;
        Long        rangerRoleVersionInDB = getRoleVersion(serviceName);

        if (LOG.isDebugEnabled()) {
            LOG.debug("==> RoleDBStore.getRangerRoles() lastKnownRoleVersion= " + lastKnownRoleVersion + " rangerRoleVersionInDB= " + rangerRoleVersionInDB);
        }

        if (rangerRoleVersionInDB != null) {
            ret = RangerRoleCache.getInstance().getLatestRangerRoleOrCached(serviceName, this, lastKnownRoleVersion, rangerRoleVersionInDB);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<= RoleDBStore.getRangerRoles() lastKnownRoleVersion= " + lastKnownRoleVersion + " rangerRoleVersionInDB= " + rangerRoleVersionInDB + " RangerRoles= " + ret);
        }

        return ret;
    }

    @Override
    public Long getRoleVersion(String serviceName) {
        Long ret = null;

        if (ServiceDBStore.isSupportsRolesDownloadByService()) {
            XXServiceVersionInfo xxServiceVersionInfo = daoMgr.getXXServiceVersionInfo().findByServiceName(serviceName);
            ret = (xxServiceVersionInfo != null) ? xxServiceVersionInfo.getRoleVersion() : null;
        } else {
            ret = daoMgr.getXXGlobalState().getRoleVersion(RANGER_ROLE_GLOBAL_STATE_NAME);
        }

        return ret;
    }

    public Set<RangerRole> getRoleNames(String userName, Set<String> userGroups) throws Exception{
        Set<RangerRole> ret = new HashSet<>();
        if (StringUtils.isNotEmpty(userName)) {
            List<XXRoleRefUser> xxRoleRefUsers = roleRefUpdater.daoMgr.getXXRoleRefUser().findByUserName(userName);
            for (XXRoleRefUser xxRoleRefUser : xxRoleRefUsers) {
                ret.add(getRole(xxRoleRefUser.getRoleId()));
            }
        }
        for(String userGroup : userGroups) {
            List<XXRoleRefGroup> xxRoleRefGroups = roleRefUpdater.daoMgr.getXXRoleRefGroup().findByGroupName(userGroup);
            for (XXRoleRefGroup xxRoleRefGroup : xxRoleRefGroups) {
                ret.add(getRole(xxRoleRefGroup.getRoleId()));
            }
        }

        return ret;
    }

    public List<RangerRole> getRoles(String serviceName) {
        List<RangerRole> ret = ListUtils.EMPTY_LIST;
        if (StringUtils.isNotEmpty(serviceName)) {
            XXService xxService = daoMgr.getXXService().findByName(serviceName);
            ret = getRoles(xxService);
        }
        return ret;
    }

    public List<RangerRole> getRoles(Long serviceId) {
        List<RangerRole> ret = ListUtils.EMPTY_LIST;

        if (serviceId != null) {
            String       serviceTypeName            = daoMgr.getXXServiceDef().findServiceDefTypeByServiceId(serviceId);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Service Type for serviceId (" + serviceId + ") = " + serviceTypeName);
            }
            String       serviceTypesToGetAllRoles  = RangerConfiguration.getInstance().get("ranger.admin.service.types.for.returning.all.roles", "solr");

            boolean      getAllRoles                = false;
            if (StringUtils.isNotEmpty(serviceTypesToGetAllRoles)) {
                String[] allRolesServiceTypes = StringUtils.split(serviceTypesToGetAllRoles, ",");
                if (allRolesServiceTypes != null) {
                    for (String allRolesServiceType : allRolesServiceTypes) {
                        if (StringUtils.equalsIgnoreCase(serviceTypeName, allRolesServiceType)) {
                            getAllRoles = true;
                            break;
                        }
                    }
                }
            }
            List<XXRole> rolesFromDb = getAllRoles ? daoMgr.getXXRole().getAll() : daoMgr.getXXRole().findByServiceId(serviceId);
            if (CollectionUtils.isNotEmpty(rolesFromDb)) {
                ret = new ArrayList<>();
                for (XXRole xxRole : rolesFromDb) {
                    ret.add(roleService.read(xxRole.getId()));
                }
            }
        }
        return ret;
    }

    public List<RangerRole> getRoles(XXService service) {
        return service == null ? ListUtils.EMPTY_LIST : getRoles(service.getId());
    }

    @Override
    public boolean roleExists(Long id) throws Exception {
        XXRole role = daoMgr.getXXRole().findByRoleId(id);
        return role != null;
    }

    @Override
    public boolean roleExists(String name) throws Exception {
        XXRole role = daoMgr.getXXRole().findByRoleName(name);
        return role != null;
    }
}

