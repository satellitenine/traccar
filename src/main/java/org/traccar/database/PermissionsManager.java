/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
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
 * limitations under the License.
 */
package org.traccar.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.api.security.PermissionsService;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PermissionsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionsManager.class);

    private final DataManager dataManager;
    private final Storage storage;

    private volatile Server server;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<Long, Set<Long>> groupPermissions = new HashMap<>();
    private final Map<Long, Set<Long>> devicePermissions = new HashMap<>();
    private final Map<Long, Set<Long>> deviceUsers = new HashMap<>();
    private final Map<Long, Set<Long>> groupDevices = new HashMap<>();

    public PermissionsManager(DataManager dataManager, Storage storage) {
        this.dataManager = dataManager;
        this.storage = storage;
        refreshServer();
        refreshDeviceAndGroupPermissions();
    }

    protected final void readLock() {
        lock.readLock().lock();
    }

    protected final void readUnlock() {
        lock.readLock().unlock();
    }

    protected final void writeLock() {
        lock.writeLock().lock();
    }

    protected final void writeUnlock() {
        lock.writeLock().unlock();
    }

    public User getUser(long userId) {
        try {
            return storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", "id", userId)));
        } catch (StorageException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Long> getGroupPermissions(long userId) {
        readLock();
        try {
            if (!groupPermissions.containsKey(userId)) {
                groupPermissions.put(userId, new HashSet<>());
            }
            return groupPermissions.get(userId);
        } finally {
            readUnlock();
        }
    }

    public Set<Long> getDevicePermissions(long userId) {
        readLock();
        try {
            if (!devicePermissions.containsKey(userId)) {
                devicePermissions.put(userId, new HashSet<>());
            }
            return devicePermissions.get(userId);
        } finally {
            readUnlock();
        }
    }

    private Set<Long> getAllDeviceUsers(long deviceId) {
        readLock();
        try {
            if (!deviceUsers.containsKey(deviceId)) {
                deviceUsers.put(deviceId, new HashSet<>());
            }
            return deviceUsers.get(deviceId);
        } finally {
            readUnlock();
        }
    }

    public Set<Long> getDeviceUsers(long deviceId) {
        Device device = Context.getIdentityManager().getById(deviceId);
        if (device != null && !device.getDisabled()) {
            return getAllDeviceUsers(deviceId);
        } else {
            Set<Long> result = new HashSet<>();
            for (long userId : getAllDeviceUsers(deviceId)) {
                if (getUserAdmin(userId)) {
                    result.add(userId);
                }
            }
            return result;
        }
    }

    public Set<Long> getGroupDevices(long groupId) {
        readLock();
        try {
            if (!groupDevices.containsKey(groupId)) {
                groupDevices.put(groupId, new HashSet<>());
            }
            return groupDevices.get(groupId);
        } finally {
            readUnlock();
        }
    }

    public void refreshServer() {
        try {
            server = dataManager.getServer();
        } catch (StorageException error) {
            LOGGER.warn("Refresh server config error", error);
        }
    }

    public final void refreshDeviceAndGroupPermissions() {
        writeLock();
        try {
            groupPermissions.clear();
            devicePermissions.clear();
            try {
                GroupTree groupTree = new GroupTree(Context.getGroupsManager().getItems(
                        Context.getGroupsManager().getAllItems()),
                        Context.getDeviceManager().getAllDevices());
                for (Permission groupPermission : dataManager.getPermissions(User.class, Group.class)) {
                    Set<Long> userGroupPermissions = getGroupPermissions(groupPermission.getOwnerId());
                    Set<Long> userDevicePermissions = getDevicePermissions(groupPermission.getOwnerId());
                    userGroupPermissions.add(groupPermission.getPropertyId());
                    for (Group group : groupTree.getGroups(groupPermission.getPropertyId())) {
                        userGroupPermissions.add(group.getId());
                    }
                    for (Device device : groupTree.getDevices(groupPermission.getPropertyId())) {
                        userDevicePermissions.add(device.getId());
                    }
                }

                for (Permission devicePermission : dataManager.getPermissions(User.class, Device.class)) {
                    getDevicePermissions(devicePermission.getOwnerId()).add(devicePermission.getPropertyId());
                }

                groupDevices.clear();
                for (long groupId : Context.getGroupsManager().getAllItems()) {
                    for (Device device : groupTree.getDevices(groupId)) {
                        getGroupDevices(groupId).add(device.getId());
                    }
                }

            } catch (StorageException | ClassNotFoundException error) {
                LOGGER.warn("Refresh device permissions error", error);
            }

            deviceUsers.clear();
            for (Map.Entry<Long, Set<Long>> entry : devicePermissions.entrySet()) {
                for (long deviceId : entry.getValue()) {
                    getAllDeviceUsers(deviceId).add(entry.getKey());
                }
            }
        } finally {
            writeUnlock();
        }
    }

    public boolean getUserAdmin(long userId) {
        User user = getUser(userId);
        return user != null && user.getAdministrator();
    }

    public void checkAdmin(long userId) throws SecurityException {
        if (!getUserAdmin(userId)) {
            throw new SecurityException("Admin access required");
        }
    }

    public boolean getUserManager(long userId) {
        User user = getUser(userId);
        return user != null && user.getUserLimit() != 0;
    }

    public void checkManager(long userId) throws SecurityException {
        if (!getUserManager(userId)) {
            throw new SecurityException("Manager access required");
        }
    }

    public boolean getUserReadonly(long userId) {
        User user = getUser(userId);
        return user != null && user.getReadonly();
    }

    public void checkReadonly(long userId) throws SecurityException {
        if (!getUserAdmin(userId) && (server.getReadonly() || getUserReadonly(userId))) {
            throw new SecurityException("Account is readonly");
        }
    }

    public void checkUserEnabled(long userId) throws SecurityException {
        User user = getUser(userId);
        if (user == null) {
            throw new SecurityException("Unknown account");
        }
        if (user.getDisabled()) {
            throw new SecurityException("Account is disabled");
        }
        if (user.getExpirationTime() != null && System.currentTimeMillis() > user.getExpirationTime().getTime()) {
            throw new SecurityException("Account has expired");
        }
    }

    public void checkDevice(long userId, long deviceId) throws SecurityException {
        try {
            new PermissionsService(storage).checkPermission(Device.class, userId, deviceId);
        } catch (StorageException e) {
            throw new RuntimeException(e);
        }
    }

    public void refreshPermissions(Permission permission) {
        if (permission.getOwnerClass().equals(User.class)) {
            if (permission.getPropertyClass().equals(Device.class)
                    || permission.getPropertyClass().equals(Group.class)) {
                refreshDeviceAndGroupPermissions();
            }
        }
    }

    public Server getServer() {
        return server;
    }

    public void updateServer(Server server) throws StorageException {
        dataManager.updateObject(server);
        this.server = server;
    }

    public User login(String email, String password) throws StorageException {
        User user = dataManager.login(email, password);
        if (user != null) {
            checkUserEnabled(user.getId());
            return getUser(user.getId());
        }
        return null;
    }

}
