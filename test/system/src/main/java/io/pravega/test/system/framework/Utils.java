/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system.framework;

import com.google.common.collect.ImmutableMap;
import io.pravega.client.ClientConfig;
import io.pravega.client.stream.impl.DefaultCredentials;
import io.pravega.test.system.framework.services.Service;
import io.pravega.test.system.framework.services.docker.BookkeeperDockerService;
import io.pravega.test.system.framework.services.docker.HDFSDockerService;
import io.pravega.test.system.framework.services.docker.PravegaControllerDockerService;
import io.pravega.test.system.framework.services.docker.PravegaSegmentStoreDockerService;
import io.pravega.test.system.framework.services.docker.ZookeeperDockerService;
import io.pravega.test.system.framework.services.kubernetes.BookkeeperK8sService;
import io.pravega.test.system.framework.services.kubernetes.PravegaControllerK8sService;
import io.pravega.test.system.framework.services.kubernetes.PravegaSegmentStoreK8sService;
import io.pravega.test.system.framework.services.kubernetes.ZookeeperK8sService;
import io.pravega.test.system.framework.services.marathon.BookkeeperService;
import io.pravega.test.system.framework.services.marathon.PravegaControllerService;
import io.pravega.test.system.framework.services.marathon.PravegaSegmentStoreService;
import io.pravega.test.system.framework.services.marathon.ZookeeperService;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * Utility methods used inside the TestFramework.
 */
@Slf4j
public class Utils {

    public static final int DOCKER_CONTROLLER_PORT = 9090;
    public static final int MARATHON_CONTROLLER_PORT = 9092;
    public static final int REST_PORT = 9091;
    public static final String DOCKER_NETWORK = "docker-network";
    public static final boolean DOCKER_BASED = Utils.isDockerExecEnabled();
    public static final int ALTERNATIVE_CONTROLLER_PORT = 9093;
    public static final int ALTERNATIVE_REST_PORT = 9094;
    public static final TestExecutorFactory.TestExecutorType EXECUTOR_TYPE = TestExecutorFactory.getTestExecutionType();
    public static final boolean AUTH_ENABLED = isAuthEnabled();
    public static final String PROPERTIES_FILE = "pravega.properties";
    public static final String PROPERTIES_FILE_WITH_AUTH = "pravega_withAuth.properties";
    public static final ImmutableMap<String, String> PRAVEGA_PROPERTIES = readPravegaProperties();

    /**
     * Get Configuration from environment or system property.
     * @param key Configuration key
     * @param defaultValue default value incase the property/env is not set
     * @return the configuration value.
     */
    public static String getConfig(final String key, final String defaultValue) {
        return System.getenv().getOrDefault(key, System.getProperty(key, defaultValue));
    }

    public static Service createZookeeperService() {
        String serviceId = "zookeeper";
        switch (EXECUTOR_TYPE) {
            case REMOTE_SEQUENTIAL:
                return new ZookeeperService(serviceId);
            case DOCKER:
                return new ZookeeperDockerService(serviceId);
            case KUBERNETES:
            default:
                return new ZookeeperK8sService(serviceId);

        }
    }

    public static Service createBookkeeperService(final URI zkUri) {
        String serviceId = "bookkeeper";
        switch (EXECUTOR_TYPE) {
            case REMOTE_SEQUENTIAL:
                return new BookkeeperService(serviceId, zkUri);
            case DOCKER:
                return new BookkeeperDockerService(serviceId, zkUri);
            case KUBERNETES:
            default:
                return new BookkeeperK8sService(serviceId, zkUri, getPravegaProperties());
        }
    }

    public static Service createPravegaControllerService(final URI zkUri, String serviceName) {
        switch (EXECUTOR_TYPE) {
            case REMOTE_SEQUENTIAL:
                return new PravegaControllerService(serviceName, zkUri);
            case DOCKER:
                return new PravegaControllerDockerService(serviceName, zkUri);
            case KUBERNETES:
            default:
                return new PravegaControllerK8sService(serviceName, zkUri, getPravegaProperties());
        }

    }

    public static Service createPravegaControllerService(final URI zkUri) {
        return createPravegaControllerService(zkUri, "controller");
    }

    public static Service createPravegaSegmentStoreService(final URI zkUri, final URI contUri) {
        URI hdfsUri = null;
        if (DOCKER_BASED) {
            Service hdfsService = new HDFSDockerService("hdfs");
            if (!hdfsService.isRunning()) {
                hdfsService.start(true);
            }
            hdfsUri = hdfsService.getServiceDetails().get(0);
        }

        String serviceId = "segmentstore";
        switch (EXECUTOR_TYPE) {
            case REMOTE_SEQUENTIAL:
                return new PravegaSegmentStoreService(serviceId, zkUri, contUri);
            case DOCKER:
                return  new PravegaSegmentStoreDockerService(serviceId, zkUri, hdfsUri, contUri);
            case KUBERNETES:
            default:
                return new PravegaSegmentStoreK8sService(serviceId, zkUri, getPravegaProperties());
        }
    }

    private static ImmutableMap<String, String> getPravegaProperties() {
        return PRAVEGA_PROPERTIES;
    }

    private static ImmutableMap<String, String> readPravegaProperties() {
        String resourceName = PROPERTIES_FILE;
        if (AUTH_ENABLED) {
            resourceName = PROPERTIES_FILE_WITH_AUTH;
        }
        Properties props = new Properties();
        try {
            props.load(Utils.class.getClassLoader().getResourceAsStream(resourceName));
        } catch (IOException e) {
            log.error("Error reading properties file.", e);
        }
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        props.forEach((key, value) -> builder.put(key.toString(), value.toString()));
        return builder.build();
    }

    public static ClientConfig buildClientConfig(URI controllerUri) {
        if (!AUTH_ENABLED) {
            log.debug("Generating config with auth disabled.");
            return ClientConfig.builder().controllerURI(controllerUri).build();
        } else {
            log.debug("Generating config with auth enabled.");
            return ClientConfig.builder()
                               // auth
                               .credentials(new DefaultCredentials("1111_aaaa", "admin"))
                               .controllerURI(controllerUri)
                               .build();
        }

    }

    /**
     * Helper method to create the Pravega Cluster Spec which specifies just those values in the spec which need to be patched.
     * Other values remain same as were specified at the time of deployment.
     * @param service Name of the service to be patched (bookkeeper/ segment store/ controller).
     * @param replicaCount Number of replicas.
     * @param component Name of the component (pravega/ bookkeeper).
     * @param namespace Namespace.
     * @param name Name of the object.
     * @param kind Kind of the object.
     *
     * @return the new Pravega Cluster Spec containing the values that need to be patched.
     */
    public static Map<String, Object> buildPatchedPravegaClusterSpec(String service, int replicaCount, String component, String namespace, String name, String kind) {

        final Map<String, Object> componentSpec = ImmutableMap.<String, Object>builder()
                .put(service, replicaCount)
                .build();

        return ImmutableMap.<String, Object>builder()
                .put("apiVersion", "pravega.pravega.io/v1alpha1")
                .put("kind", kind)
                .put("metadata", ImmutableMap.of("name", name, "namespace", namespace))
                .put("spec", ImmutableMap.builder()
                        .put(component, componentSpec)
                        .build())
                .build();
    }

    /**
     * Helper method to check if skipServiceInstallation flag is set.
     * This flag indicates if the system test framework should reuse services already deployed on the cluster.
     * if set to
     *  true: Already deployed services are used for running tests.
     *  false: Services are deployed on the cluster before running tests.
     *
     * Default value is false
     * @return true if skipServiceInstallation is set, false otherwise.
     */

    public static boolean isSkipServiceInstallationEnabled() {
        String config = getConfig("skipServiceInstallation", "false");
        return config.trim().equalsIgnoreCase("true") ? true : false;
    }

    public static boolean isDockerExecEnabled() {
        String dockerConfig = getConfig("execType", "LOCAL");
        return dockerConfig.trim().equalsIgnoreCase("docker") ?  true : false;

    }

    public static boolean isAwsExecution() {
        String dockerConfig = getConfig("awsExec", "false");
        return dockerConfig.trim().equalsIgnoreCase("true") ?  true : false;
    }

    private static boolean isAuthEnabled() {
        String securityEnabled = Utils.getConfig("securityEnabled", "false");
        return Boolean.valueOf(securityEnabled);
    }



}
