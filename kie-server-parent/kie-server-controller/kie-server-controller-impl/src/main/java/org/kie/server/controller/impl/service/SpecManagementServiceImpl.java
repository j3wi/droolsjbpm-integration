/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.server.controller.impl.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.kie.server.api.model.KieContainerStatus;
import org.kie.server.api.model.KieScannerStatus;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.controller.api.KieServerControllerException;
import org.kie.server.controller.api.KieServerControllerNotFoundException;
import org.kie.server.controller.api.model.events.ServerTemplateDeleted;
import org.kie.server.controller.api.model.events.ServerTemplateUpdated;
import org.kie.server.controller.api.model.runtime.Container;
import org.kie.server.controller.api.model.spec.Capability;
import org.kie.server.controller.api.model.spec.ContainerConfig;
import org.kie.server.controller.api.model.spec.ContainerSpec;
import org.kie.server.controller.api.model.spec.ContainerSpecKey;
import org.kie.server.controller.api.model.spec.ProcessConfig;
import org.kie.server.controller.api.model.spec.RuleConfig;
import org.kie.server.controller.api.model.spec.ServerConfig;
import org.kie.server.controller.api.model.spec.ServerTemplate;
import org.kie.server.controller.api.model.spec.ServerTemplateKey;
import org.kie.server.controller.api.service.NotificationService;
import org.kie.server.controller.api.service.SpecManagementService;
import org.kie.server.controller.api.storage.KieServerTemplateStorage;
import org.kie.server.controller.impl.KieServerInstanceManager;
import org.kie.server.controller.impl.storage.InMemoryKieServerTemplateStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpecManagementServiceImpl implements SpecManagementService {

    private static Logger logger = LoggerFactory.getLogger(SpecManagementServiceImpl.class);
    private KieServerInstanceManager kieServerInstanceManager = KieServerInstanceManager.getInstance();
    private KieServerTemplateStorage templateStorage = InMemoryKieServerTemplateStorage.getInstance();
    private NotificationService notificationService = LoggingNotificationService.getInstance();

    @Override
    public synchronized void saveContainerSpec(String serverTemplateId,
                                               ContainerSpec containerSpec) {
        ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        if (serverTemplate.hasContainerSpec(containerSpec.getId())) {
            throw new KieServerControllerException("Server template with id " + serverTemplateId + " associated already with container " + containerSpec.getId());
        }
        // make sure correct server template is set
        containerSpec.setServerTemplateKey(new ServerTemplateKey(serverTemplate.getId(), serverTemplate.getName()));

        serverTemplate.addContainerSpec(containerSpec);

        templateStorage.update(serverTemplate);

        notificationService.notify(new ServerTemplateUpdated(serverTemplate));

        if (containerSpec.getStatus().equals(KieContainerStatus.STARTED)) {
            List<Container> containers = kieServerInstanceManager.startContainer(serverTemplate, containerSpec);
            notificationService.notify(serverTemplate, containerSpec, containers);
        }
    }

    @Override
    public synchronized void updateContainerSpec(String serverTemplateId, ContainerSpec containerSpec) {
        updateContainerSpec(serverTemplateId, containerSpec.getId(), containerSpec);
    }

    @Override
    public synchronized void updateContainerSpec(final String serverTemplateId, final String containerId, final ContainerSpec containerSpec) {
        ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);

        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        if (!containerSpec.getId().equals(containerId)) {
            throw new KieServerControllerException("Cannot update container " + containerSpec.getId() + " on container " + containerId);
        }

        if (!serverTemplate.hasContainerSpec(containerSpec.getId())) {
            throw new KieServerControllerNotFoundException("Server template with id " + serverTemplateId + " has no container with id " + containerSpec.getId());
        }

        if (!serverTemplate.hasMatchingId(containerSpec.getServerTemplateKey())) {
            throw new KieServerControllerException("Cannot change container template key during update.");
        }

        // make sure correct server template is set
        containerSpec.setServerTemplateKey(new ServerTemplateKey(serverTemplate.getId(), serverTemplate.getName()));

        ContainerSpec currentVersion = serverTemplate.getContainerSpec(containerSpec.getId());

        serverTemplate.deleteContainerSpec(currentVersion.getId());
        serverTemplate.addContainerSpec(containerSpec);

        templateStorage.update(serverTemplate);

        notificationService.notify(new ServerTemplateUpdated(serverTemplate));
        // in case container was started before it was update or update comes with status started update container in running servers
        if (currentVersion.getStatus().equals(KieContainerStatus.STARTED) || containerSpec.getStatus().equals(KieContainerStatus.STARTED)) {
            List<Container> containers = kieServerInstanceManager.upgradeAndStartContainer(serverTemplate, containerSpec);
            notificationService.notify(serverTemplate, containerSpec, containers);
        }
    }

    @Override
    public synchronized void saveServerTemplate(ServerTemplate serverTemplate) {
        if (templateStorage.exists(serverTemplate.getId())) {
            templateStorage.update(serverTemplate);
        } else {

            templateStorage.store(serverTemplate);
        }

        notificationService.notify(new ServerTemplateUpdated(serverTemplate));

        Collection<ContainerSpec> containerSpecs = serverTemplate.getContainersSpec();
        if (containerSpecs != null && !containerSpecs.isEmpty()) {
            for (ContainerSpec containerSpec : containerSpecs) {
                if (containerSpec.getStatus().equals(KieContainerStatus.STARTED)) {
                    List<Container> containers = kieServerInstanceManager.startContainer(serverTemplate, containerSpec);
                    notificationService.notify(serverTemplate, containerSpec, containers);
                }
            }
        }
    }

    @Override
    public ServerTemplate getServerTemplate(String serverTemplateId) {
        return templateStorage.load(serverTemplateId);
    }

    @Override
    public Collection<ServerTemplateKey> listServerTemplateKeys() {
        return templateStorage.loadKeys();
    }

    @Override
    public Collection<ServerTemplate> listServerTemplates() {
        return templateStorage.load();
    }

    @Override
    public Collection<ContainerSpec> listContainerSpec(String serverTemplateId) {
        ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        return serverTemplate.getContainersSpec();
    }

    @Override
    public ContainerSpec getContainerInfo(String serverTemplateId,
                                          String containerId) {
        final ServerTemplate serverTemplate = getServerTemplate(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }
        return serverTemplate.getContainerSpec(containerId);
    }

    @Override
    public synchronized void deleteContainerSpec(String serverTemplateId,
                                                 String containerSpecId) {
        ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        if (serverTemplate.hasContainerSpec(containerSpecId)) {
            ContainerSpec containerSpec = serverTemplate.getContainerSpec(containerSpecId);
            kieServerInstanceManager.stopContainer(serverTemplate, containerSpec);
            serverTemplate.deleteContainerSpec(containerSpecId);

            templateStorage.update(serverTemplate);

            notificationService.notify(new ServerTemplateUpdated(serverTemplate));
        } else {
            throw new KieServerControllerNotFoundException("Container " + containerSpecId + " not found");
        }
    }

    @Override
    public synchronized void deleteServerTemplate(String serverTemplateId) {
        if (!templateStorage.exists(serverTemplateId)) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        templateStorage.delete(serverTemplateId);

        notificationService.notify(new ServerTemplateDeleted(serverTemplateId));
    }

    @Override
    public synchronized void copyServerTemplate(String serverTemplateId,
                                                String newServerTemplateId,
                                                String newServerTemplateName) {
        final ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        final Map<Capability, ServerConfig> configMap = new HashMap<Capability, ServerConfig>(serverTemplate.getConfigs().size());
        for (final Map.Entry<Capability, ServerConfig> entry : serverTemplate.getConfigs().entrySet()) {
            configMap.put(entry.getKey(), copy(entry.getValue()));
        }

        final Collection<ContainerSpec> containerSpecs = new ArrayList<ContainerSpec>(serverTemplate.getContainersSpec().size());
        for (final ContainerSpec entry : serverTemplate.getContainersSpec()) {
            containerSpecs.add(copy(entry, newServerTemplateId, newServerTemplateName));
        }

        final ServerTemplate copy = new ServerTemplate(newServerTemplateId,
                                                       newServerTemplateName,
                                                       serverTemplate.getCapabilities(),
                                                       configMap,
                                                       containerSpecs);

        templateStorage.store(copy);
    }

    private ContainerSpec copy(final ContainerSpec origin,
                               final String newServerTemplateId,
                               final String newServerTemplateName) {
        final Map<Capability, ContainerConfig> configMap = origin.getConfigs();
        for (Map.Entry<Capability, ContainerConfig> entry : origin.getConfigs().entrySet()) {
            configMap.put(entry.getKey(), copy(entry.getValue()));
        }
        return new ContainerSpec(origin.getId(),
                                 origin.getContainerName(),
                                 new ServerTemplateKey(newServerTemplateId, newServerTemplateName),
                                 new ReleaseId(origin.getReleasedId()),
                                 origin.getStatus(),
                                 configMap);
    }

    private ContainerConfig copy(final ContainerConfig _value) {
        if (_value instanceof RuleConfig) {
            final RuleConfig value = (RuleConfig) _value;
            return new RuleConfig(value.getPollInterval(), value.getScannerStatus());
        } else if (_value instanceof ProcessConfig) {
            final ProcessConfig value = (ProcessConfig) _value;
            return new ProcessConfig(value.getRuntimeStrategy(), value.getKBase(), value.getKSession(), value.getMergeMode());
        }
        return null;
    }

    private ServerConfig copy(final ServerConfig value) {
        return new ServerConfig();
    }

    @Override
    public synchronized void updateContainerConfig(final String serverTemplateId,
                                                   final String containerSpecId,
                                                   final Capability capability,
                                                   final ContainerConfig containerConfig) {

        final ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        final ContainerSpec containerSpec = serverTemplate.getContainerSpec(containerSpecId);
        if (containerSpec == null) {
            throw new KieServerControllerNotFoundException("No container spec found for id " + containerSpecId + " within server template with id " + serverTemplateId);
        }

        final List<Container> affectedContainers = updateContainerConfig(capability, containerConfig, serverTemplate, containerSpec);

        if (affectedContainers.isEmpty()) {
            logInfo("Update of container configuration resulted in no changes to containers running on kie-servers");
        }

        affectedContainers.forEach(ac -> {
            logDebug("Container {} on server {} was affected by a change in the scanner", ac.getContainerSpecId(), ac.getServerInstanceKey());
        });

        containerSpec.getConfigs().put(capability, containerConfig);

        templateStorage.update(serverTemplate);

        notificationService.notify(new ServerTemplateUpdated(serverTemplate));
    }

    void logInfo(final String message) {
        logger.info(message);
    }

    void logDebug(final String message,
                  final Object... objects) {
        logger.debug(message, objects);
    }

    List<Container> updateContainerConfig(final Capability capability,
                                          final ContainerConfig containerConfig,
                                          final ServerTemplate serverTemplate,
                                          final ContainerSpec containerSpec) {

        if (containerConfig instanceof RuleConfig) {

            final RuleConfig ruleConfig = (RuleConfig) containerConfig;

            return updateContainerRuleConfig(ruleConfig, serverTemplate, containerSpec);
        } else if (containerConfig instanceof ProcessConfig) {

            final ProcessConfig processConfig = (ProcessConfig) containerConfig;

            return updateContainerProcessConfig(processConfig, capability, serverTemplate, containerSpec);
        } else {
            return new ArrayList<>();
        }
    }

    List<Container> updateContainerProcessConfig(final ProcessConfig processConfig,
                                                 final Capability capability,
                                                 final ServerTemplate serverTemplate,
                                                 final ContainerSpec containerSpec) {

        containerSpec.getConfigs().put(capability, processConfig);

        return kieServerInstanceManager.upgradeContainer(serverTemplate, containerSpec);
    }

    List<Container> updateContainerRuleConfig(final RuleConfig ruleConfig,
                                              final ServerTemplate serverTemplate,
                                              final ContainerSpec containerSpec) {

        final Long interval = ruleConfig.getPollInterval();
        final KieScannerStatus status = ruleConfig.getScannerStatus();

        switch (status) {
            case STARTED:
                return kieServerInstanceManager.startScanner(serverTemplate, containerSpec, interval);
            case STOPPED:
                return kieServerInstanceManager.stopScanner(serverTemplate, containerSpec);
            default:
                return new ArrayList<>();
        }
    }

    @Override
    public synchronized void updateServerTemplateConfig(String serverTemplateId,
                                                        Capability capability,
                                                        ServerConfig serverTemplateConfig) {
        ServerTemplate serverTemplate = templateStorage.load(serverTemplateId);
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + serverTemplateId);
        }

        Map<Capability, ServerConfig> configs = serverTemplate.getConfigs();
        configs.put(capability, serverTemplateConfig);
        serverTemplate.setConfigs(configs);

        templateStorage.update(serverTemplate);

        notificationService.notify(new ServerTemplateUpdated(serverTemplate));
    }

    @Override
    public synchronized void startContainer(ContainerSpecKey containerSpecKey) {
        ServerTemplate serverTemplate = templateStorage.load(containerSpecKey.getServerTemplateKey().getId());
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + containerSpecKey.getServerTemplateKey().getId());
        }

        final ContainerSpec containerSpec = serverTemplate.getContainerSpec(containerSpecKey.getId());
        if (containerSpec == null) {
            throw new KieServerControllerNotFoundException("No container spec found for id " + containerSpecKey.getId()
                                                                   + " within server template with id " + serverTemplate.getId());
        }
        containerSpec.setStatus(KieContainerStatus.STARTED);

        templateStorage.update(serverTemplate);

        List<Container> containers = kieServerInstanceManager.startContainer(serverTemplate, containerSpec);

        notificationService.notify(serverTemplate, containerSpec, containers);
    }

    @Override
    public synchronized void stopContainer(ContainerSpecKey containerSpecKey) {

        ServerTemplate serverTemplate = templateStorage.load(containerSpecKey.getServerTemplateKey().getId());
        if (serverTemplate == null) {
            throw new KieServerControllerNotFoundException("No server template found for id " + containerSpecKey.getServerTemplateKey().getId());
        }

        ContainerSpec containerSpec = serverTemplate.getContainerSpec(containerSpecKey.getId());
        if (containerSpec == null) {
            throw new KieServerControllerNotFoundException("No container spec found for id " + containerSpecKey.getId() + " within server template with id " + serverTemplate.getId());
        }
        containerSpec.setStatus(KieContainerStatus.STOPPED);

        templateStorage.update(serverTemplate);

        List<Container> containers = kieServerInstanceManager.stopContainer(serverTemplate, containerSpec);

        notificationService.notify(serverTemplate, containerSpec, containers);
    }

    public KieServerTemplateStorage getTemplateStorage() {
        return templateStorage;
    }

    public void setTemplateStorage(KieServerTemplateStorage templateStorage) {
        this.templateStorage = templateStorage;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public KieServerInstanceManager getKieServerInstanceManager() {
        return kieServerInstanceManager;
    }

    public void setKieServerInstanceManager(KieServerInstanceManager kieServerInstanceManager) {
        this.kieServerInstanceManager = kieServerInstanceManager;
    }
}
