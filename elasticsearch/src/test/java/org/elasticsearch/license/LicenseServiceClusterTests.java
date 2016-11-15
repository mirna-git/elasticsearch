/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.transport.Netty3Plugin;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.xpack.XPackPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

import static org.elasticsearch.test.ESIntegTestCase.Scope.TEST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;

@ClusterScope(scope = TEST, numDataNodes = 0, numClientNodes = 0, maxNumDataNodes = 0, transportClientRatio = 0, autoMinMasterNodes = false)
public class LicenseServiceClusterTests extends AbstractLicensesIntegrationTestCase {

    @Override
    protected Settings transportClientSettings() {
        return super.transportClientSettings();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return nodeSettingsBuilder(nodeOrdinal).build();
    }

    private Settings.Builder nodeSettingsBuilder(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("node.data", true)
                .put("resource.reload.interval.high", "500ms") // for license mode file watcher
                .put(NetworkModule.HTTP_ENABLED.getKey(), true);
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(XPackPlugin.class, Netty3Plugin.class, Netty4Plugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    public void testClusterRestartWithLicense() throws Exception {
        wipeAllLicenses();

        int numNodes = randomIntBetween(1, 5);
        logger.info("--> starting {} node(s)", numNodes);
        for (int i = 0; i < numNodes; i++) {
            internalCluster().startNode();
        }
        ensureGreen();

        logger.info("--> put signed license");
        LicensingClient licensingClient = new LicensingClient(client());
        License license = TestUtils.generateSignedLicense(TimeValue.timeValueMinutes(1));
        putLicense(license);
        assertThat(licensingClient.prepareGetLicense().get().license(), equalTo(license));
        assertOperationMode(license.operationMode());

        logger.info("--> restart all nodes");
        internalCluster().fullRestart();
        ensureYellow();
        licensingClient = new LicensingClient(client());
        logger.info("--> get and check signed license");
        assertThat(licensingClient.prepareGetLicense().get().license(), equalTo(license));
        logger.info("--> remove licenses");
        licensingClient.prepareDeleteLicense().get();
        assertOperationMode(License.OperationMode.MISSING);

        logger.info("--> restart all nodes");
        internalCluster().fullRestart();
        licensingClient = new LicensingClient(client());
        ensureYellow();
        assertThat(licensingClient.prepareGetLicense().get().license(), nullValue());
        assertOperationMode(License.OperationMode.MISSING);


        wipeAllLicenses();
    }

    public void testCloudInternalLicense() throws Exception {
        wipeAllLicenses();

        int numNodes = randomIntBetween(1, 5);
        logger.info("--> starting {} node(s)", numNodes);
        for (int i = 0; i < numNodes; i++) {
            internalCluster().startNode();
        }
        ensureGreen();

        logger.info("--> put signed license");
        LicensingClient licensingClient = new LicensingClient(client());
        License license = TestUtils.generateSignedLicense("cloud_internal", License.VERSION_CURRENT, System.currentTimeMillis(),
                TimeValue.timeValueMinutes(1));
        putLicense(license);
        assertThat(licensingClient.prepareGetLicense().get().license(), equalTo(license));
        assertOperationMode(License.OperationMode.PLATINUM);
        writeCloudInternalMode("gold");
        assertOperationMode(License.OperationMode.GOLD);
        writeCloudInternalMode("basic");
        assertOperationMode(License.OperationMode.BASIC);
    }

    public void testClusterRestartWhileEnabled() throws Exception {
        wipeAllLicenses();
        internalCluster().startNode();
        ensureGreen();
        assertLicenseActive(true);
        logger.info("--> restart node");
        internalCluster().fullRestart();
        ensureYellow();
        logger.info("--> await node for enabled");
        assertLicenseActive(true);
    }

    public void testClusterRestartWhileGrace() throws Exception {
        wipeAllLicenses();
        internalCluster().startNode();
        assertLicenseActive(true);
        putLicense(TestUtils.generateSignedLicense(TimeValue.timeValueMillis(0)));
        ensureGreen();
        assertLicenseActive(true);
        logger.info("--> restart node");
        internalCluster().fullRestart();
        ensureYellow();
        logger.info("--> await node for grace_period");
        assertLicenseActive(true);
    }

    public void testClusterRestartWhileExpired() throws Exception {
        wipeAllLicenses();
        internalCluster().startNode();
        ensureGreen();
        assertLicenseActive(true);
        putLicense(TestUtils.generateExpiredLicense(System.currentTimeMillis() - LicenseService.GRACE_PERIOD_DURATION.getMillis()));
        assertLicenseActive(false);
        logger.info("--> restart node");
        internalCluster().fullRestart();
        ensureYellow();
        logger.info("--> await node for disabled");
        assertLicenseActive(false);
    }

    public void testClusterNotRecovered() throws Exception {
        logger.info("--> start one master out of two [recovery state]");
        internalCluster().startNode(nodeSettingsBuilder(0).put("discovery.zen.minimum_master_nodes", 2).put("node.master", true));
        logger.info("--> start second master out of two [recovered state]");
        internalCluster().startNode(nodeSettingsBuilder(1).put("discovery.zen.minimum_master_nodes", 2).put("node.master", true));
        assertLicenseActive(true);
    }

    private void assertLicenseActive(boolean active) throws InterruptedException {
        boolean success = awaitBusy(() -> {
            for (XPackLicenseState licenseState : internalCluster().getDataNodeInstances(XPackLicenseState.class)) {
                if (licenseState.isActive() == active) {
                    return true;
                }
            }
            return false;
        });
        assertTrue(success);
    }

    private void assertOperationMode(License.OperationMode operationMode) throws InterruptedException {
        boolean success = awaitBusy(() -> {
            for (XPackLicenseState licenseState : internalCluster().getDataNodeInstances(XPackLicenseState.class)) {
                if (licenseState.getOperationMode() == operationMode) {
                    return true;
                }
            }
            return false;
        });
        assertTrue(success);
    }

    private void writeCloudInternalMode(String mode) throws Exception {
        for (Environment environment : internalCluster().getDataOrMasterNodeInstances(Environment.class)) {
            Path licenseModePath = XPackPlugin.resolveConfigFile(environment, "license_mode");
            Files.createDirectories(licenseModePath.getParent());
            Files.write(licenseModePath, mode.getBytes(StandardCharsets.UTF_8));
        }
    }

}
