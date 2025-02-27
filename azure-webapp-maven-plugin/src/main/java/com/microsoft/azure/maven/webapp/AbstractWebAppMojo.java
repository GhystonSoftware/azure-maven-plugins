/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.maven.webapp;

import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.Subscription;
import com.microsoft.azure.common.appservice.DockerImageType;
import com.microsoft.azure.common.exceptions.AzureExecutionException;
import com.microsoft.azure.common.logging.Log;
import com.microsoft.azure.common.utils.AppServiceUtils;
import com.microsoft.azure.management.appservice.DeploymentSlot;
import com.microsoft.azure.management.appservice.WebApp;
import com.microsoft.azure.management.appservice.WebContainer;
import com.microsoft.azure.maven.AbstractAppServiceMojo;
import com.microsoft.azure.maven.auth.AzureAuthFailureException;
import com.microsoft.azure.maven.auth.MavenAuthManager;
import com.microsoft.azure.maven.model.MavenAuthConfiguration;
import com.microsoft.azure.maven.model.SubscriptionOption2;
import com.microsoft.azure.maven.utils.CustomTextIoStringListReader;
import com.microsoft.azure.maven.utils.SystemPropertyUtils;
import com.microsoft.azure.maven.webapp.configuration.ContainerSetting;
import com.microsoft.azure.maven.webapp.configuration.Deployment;
import com.microsoft.azure.maven.webapp.configuration.MavenRuntimeConfig;
import com.microsoft.azure.maven.webapp.configuration.SchemaVersion;
import com.microsoft.azure.maven.webapp.parser.AbstractConfigParser;
import com.microsoft.azure.maven.webapp.parser.V1ConfigParser;
import com.microsoft.azure.maven.webapp.parser.V2ConfigParser;
import com.microsoft.azure.maven.webapp.validator.AbstractConfigurationValidator;
import com.microsoft.azure.maven.webapp.validator.V1ConfigurationValidator;
import com.microsoft.azure.maven.webapp.validator.V2ConfigurationValidator;
import com.microsoft.azure.toolkit.lib.common.utils.TextUtils;
import com.microsoft.azure.toolkits.appservice.AzureAppService;
import com.microsoft.azure.toolkits.appservice.model.DockerConfiguration;
import com.microsoft.azure.tools.auth.exception.AzureLoginException;
import com.microsoft.azure.tools.auth.model.AzureCredentialWrapper;
import com.microsoft.azure.tools.auth.util.AzureEnvironmentUtils;
import com.microsoft.azure.tools.common.util.StringListUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugins.annotations.Parameter;
import org.beryx.textio.TextIO;
import org.beryx.textio.TextIoFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Base abstract class for Web App Mojos.
 */
public abstract class AbstractWebAppMojo extends AbstractAppServiceMojo {
    public static final String JAVA_VERSION_KEY = "javaVersion";
    public static final String JAVA_WEB_CONTAINER_KEY = "javaWebContainer";
    public static final String DOCKER_IMAGE_TYPE_KEY = "dockerImageType";
    public static final String DEPLOYMENT_TYPE_KEY = "deploymentType";
    public static final String OS_KEY = "os";
    public static final String INVALID_CONFIG_KEY = "invalidConfiguration";
    public static final String SCHEMA_VERSION_KEY = "schemaVersion";

    //region Properties

    /**
     * App Service pricing tier, which will only be used to create Web App at the first time.<p>
     * Below is the list of supported pricing tier:
     * <ul>
     *     <li>F1</li>
     *     <li>D1</li>
     *     <li>B1</li>
     *     <li>B2</li>
     *     <li>B3</li>
     *     <li>S1</li>
     *     <li>S2</li>
     *     <li>S3</li>
     *     <li>P1V2</li>
     *     <li>P2V2</li>
     *     <li>P3V2</li>
     * </ul>
     */
    @Parameter(property = "webapp.pricingTier")
    protected String pricingTier;

    /**
     * JVM version of Web App. This only applies to Windows-based Web App.<p>
     * Below is the list of supported JVM versions:
     * <ul>
     *     <li>1.7</li>
     *     <li>1.7.0_51</li>
     *     <li>1.7.0_71</li>
     *     <li>1.8</li>
     *     <li>1.8.0_25</li>
     *     <li>1.8.0_60</li>
     *     <li>1.8.0_73</li>
     *     <li>1.8.0_111</li>
     *     <li>1.8.0_92</li>
     *     <li>1.8.0_102</li>
     * </ul>
     *
     * @deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.javaVersion")
    protected String javaVersion;

    /**
     * Web container type and version within Web App. This only applies to Windows-based Web App.<p>
     * Below is the list of supported web container types:
     * <ul>
     *     <li>tomcat 7.0</li>
     *     <li>tomcat 7.0.50</li>
     *     <li>tomcat 7.0.62</li>
     *     <li>tomcat 8.0</li>
     *     <li>tomcat 8.0.23</li>
     *     <li>tomcat 8.5</li>
     *     <li>tomcat 8.5.6</li>
     *     <li>jetty 9.1</li>
     *     <li>jetty 9.1.0.20131115</li>
     *     <li>jetty 9.3</li>
     *     <li>jetty 9.3.12.20161014</li>
     * </ul>
     *
     * @deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.javaWebContainer", defaultValue = "tomcat 8.5")
    protected String javaWebContainer;

    /**
     * Below is the list of supported Linux runtime:
     * <ul>
     *     <li>tomcat 8.5-jre8</li>
     *     <li>tomcat 9.0-jre8</li>
     *     <li>jre8</li>
     * </ul>
     * @deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.linuxRuntime")
    protected String linuxRuntime;

    /**
     * Settings of docker container image within Web App. This only applies to Linux-based Web App.<p>
     * Below are the supported sub-element within {@code <containerSettings>}:<p>
     * {@code <imageName>} specifies docker image name to use in Web App on Linux<p>
     * {@code <serverId>} specifies credentials to access docker image. Use it when you are using private Docker Hub
     * image or private registry.<p>
     * {@code <registryUrl>} specifies your docker image registry URL. Use it when you are using private registry.
     *
     * @deprecated
     */
    @Deprecated
    @Parameter
    protected ContainerSetting containerSettings;

    /**
     * Flag to control whether stop Web App during deployment.
     *
     * @deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.stopAppDuringDeployment", defaultValue = "false")
    protected boolean stopAppDuringDeployment;

    /**
     * Resources to deploy to Web App.
     *
     * @deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.resources")
    protected List<Resource> resources;

    /**
     * Skip execution.
     *
     * @since 0.1.4
     */
    @Parameter(property = "webapp.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Location of the war file which is going to be deployed. If this field is not defined,
     * plugin will find the war file with the final name in the build directory.
     *
     * @Deprecated
     * @since 1.1.0
     */
    @Deprecated
    @Parameter(property = "webapp.warFile")
    protected String warFile;

    /**
     * Location of the jar file which is going to be deployed. If this field is not defined,
     * plugin will find the jar file with the final name in the build directory.
     *
     * @Deprecated
     * @since 1.3.0
     */
    @Deprecated
    @Parameter(property = "webapp.jarFile")
    protected String jarFile;

    /**
     * The context path for the deployment.
     * By default it will be deployed to '/', which is also known as the ROOT.
     *
     * @Deprecated
     */
    @Deprecated
    @Parameter(property = "webapp.path", defaultValue = "/")
    protected String path;

    /**
     * App Service region, which will only be used to create App Service at the first time.
     */
    @Parameter(property = "webapp.region")
    protected String region;

    /**
     * Schema version, which will be used to indicate the version of settings schema to use.
     *
     * @since 2.0.0
     */
    @Parameter(property = "schemaVersion", defaultValue = "v2")
    protected String schemaVersion;

    /**
     * Runtime setting
     *
     * @since 2.0.0
     */
    @Parameter(property = "runtime")
    protected MavenRuntimeConfig runtime;

    /**
     * Deployment setting
     *
     * @since 2.0.0
     */
    @Parameter(property = "deployment")
    protected Deployment deployment;

    private WebAppConfiguration webAppConfiguration;

    protected File stagingDirectory;

    protected AzureAppService az;

    private boolean isRuntimeInjected = false;
    //endregion

    //region Getter

    @Override
    protected boolean isSkipMojo() {
        return skip;
    }

    @Override
    public String getResourceGroup() {
        return resourceGroup;
    }

    @Override
    public String getAppName() {
        return appName == null ? "" : appName;
    }

    @Override
    public String getAppServicePlanResourceGroup() {
        return appServicePlanResourceGroup;
    }

    @Override
    public String getAppServicePlanName() {
        return appServicePlanName;
    }

    public String getRegion() {
        return region;
    }

    public String getPricingTier() {
        return this.pricingTier;
    }

    public String getJavaVersion() {
        return this.javaVersion;
    }

    public String getLinuxRuntime() {
        return linuxRuntime;
    }

    public WebContainer getJavaWebContainer() {
        return StringUtils.isEmpty(javaWebContainer) ?
            WebContainer.TOMCAT_8_5_NEWEST :
            WebContainer.fromString(javaWebContainer);
    }

    public ContainerSetting getContainerSettings() {
        return containerSettings;
    }

    public boolean isStopAppDuringDeployment() {
        return stopAppDuringDeployment;
    }

    @Override
    public List<Resource> getResources() {
        return resources == null ? Collections.emptyList() : resources;
    }

    public String getWarFile() {
        return warFile;
    }

    public String getJarFile() {
        return jarFile;
    }

    public String getPath() {
        return path;
    }

    public WebApp getWebApp() throws AzureAuthFailureException, AzureExecutionException {
        return getAzureClient().webApps().getByResourceGroup(getResourceGroup(), getAppName());
    }

    public DeploymentSlot getDeploymentSlot(final WebApp app, final String slotName) {
        DeploymentSlot slot = null;
        if (StringUtils.isNotEmpty(slotName)) {
            try {
                slot = app.deploymentSlots().getByName(slotName);
            } catch (NoSuchElementException deploymentSlotNotExistException) {
            }
        }
        return slot;
    }

    public boolean isDeployToDeploymentSlot() {
        return getDeploymentSlotSetting() != null;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public MavenRuntimeConfig getRuntime() {
        if (!isRuntimeInjected) {
            setRuntime((MavenRuntimeConfig) SystemPropertyUtils.injectCommandLineParameter("runtime", runtime, MavenRuntimeConfig.class));
            isRuntimeInjected = true;
        }
        return runtime;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public void setRuntime(final MavenRuntimeConfig runtime) {
        this.runtime = runtime;
    }
    //endregion

    //region Telemetry Configuration Interface

    @Override
    public Map<String, String> getTelemetryProperties() {
        final Map<String, String> map = super.getTelemetryProperties();
        final WebAppConfig webAppConfig;
        try {
            webAppConfig = getWebAppConfig();
        } catch (Exception e) {
            map.put(INVALID_CONFIG_KEY, e.getMessage());
            return map;
        }
        if (webAppConfig.getDockerConfiguration() != null) {
            final DockerConfiguration dockerConfiguration = webAppConfig.getDockerConfiguration();
            final String imageType = AppServiceUtils.getDockerImageType(dockerConfiguration.getImage(), StringUtils.isEmpty(dockerConfiguration.getPassword()),
                    dockerConfiguration.getRegistryUrl()).name();
            map.put(DOCKER_IMAGE_TYPE_KEY, imageType);
        } else {
            map.put(DOCKER_IMAGE_TYPE_KEY, DockerImageType.NONE.toString());
        }
        map.put(SCHEMA_VERSION_KEY, schemaVersion);
        map.put(OS_KEY, webAppConfig.getRuntime() == null ? "" : Objects.toString(webAppConfig.getRuntime().getOperatingSystem()));
        map.put(JAVA_VERSION_KEY, (webAppConfig.getRuntime() == null || webAppConfig.getRuntime().getJavaVersion() == null) ?
                "" : webAppConfig.getRuntime().getJavaVersion().toString());
        map.put(JAVA_WEB_CONTAINER_KEY, (webAppConfig.getRuntime() == null || webAppConfig.getRuntime().getWebContainer() == null) ?
                "" : webAppConfig.getRuntime().getWebContainer().toString());
        try {
            map.put(DEPLOYMENT_TYPE_KEY, getDeploymentType().toString());
        } catch (AzureExecutionException e) {
            map.put(DEPLOYMENT_TYPE_KEY, "Unknown deployment type.");
        }
        return map;
    }

    protected WebAppConfig getWebAppConfig() throws AzureExecutionException {
        final SchemaVersion version = SchemaVersion.fromString(getSchemaVersion());
        final AbstractConfigurationValidator validator = version == SchemaVersion.V2 ?
                new V2ConfigurationValidator(this) : new V1ConfigurationValidator(this);
        final AbstractConfigParser parser = version == SchemaVersion.V2 ? new V2ConfigParser(this, validator) : new V1ConfigParser(this, validator);
        return parser.parse();
    }

    protected AzureAppService getOrCreateAzureAppServiceClient() throws AzureExecutionException {
        try {
            final MavenAuthConfiguration mavenAuthConfiguration = auth == null ? new MavenAuthConfiguration() : auth;
            mavenAuthConfiguration.setType(getAuthType());
            final AzureCredentialWrapper azureCredentialWrapper = MavenAuthManager.getInstance().login(session, settingsDecrypter, mavenAuthConfiguration);
            if (Objects.isNull(azureCredentialWrapper)) {
                return null;
            }
            final com.microsoft.azure.AzureEnvironment env = azureCredentialWrapper.getEnv();
            final String environmentName = AzureEnvironmentUtils.azureEnvironmentToString(env);
            if (env != com.microsoft.azure.AzureEnvironment.AZURE) {
                Log.prompt(String.format(USING_AZURE_ENVIRONMENT, TextUtils.cyan(environmentName)));
            }
            Log.info(azureCredentialWrapper.getCredentialDescription());
            final AzureProfile profile = new AzureProfile(azureCredentialWrapper.getTenantId(), getSubscriptionId(),
                    convertEnvironment(azureCredentialWrapper.getEnv()));
            final AzureResourceManager.Authenticated authenticated =
                    AzureResourceManager.configure().authenticate(azureCredentialWrapper.getTokenCredential(), profile);
            final List<Subscription> subscriptions = authenticated.subscriptions().list().stream().collect(Collectors.toList());
            final String targetSubscriptionId = getTargetSubscriptionId(azureCredentialWrapper, subscriptions);
            checkSubscription(subscriptions, targetSubscriptionId);
            azureCredentialWrapper.withDefaultSubscriptionId(targetSubscriptionId);
            final AzureResourceManager azureResourceManager = authenticated.withSubscription(targetSubscriptionId);
            final Subscription subscription = azureResourceManager.getCurrentSubscription();
            if (subscription != null) {
                Log.info(String.format(SUBSCRIPTION_TEMPLATE, TextUtils.cyan(subscription.displayName()), TextUtils.cyan(subscription.subscriptionId())));
            }
            return AzureAppService.auth(azureResourceManager);
        } catch (AzureLoginException | AzureExecutionException e) {
            throw new AzureExecutionException(e.getMessage());
        }
    }

    private String getTargetSubscriptionId(AzureCredentialWrapper azureCredentialWrapper, List<Subscription> subscriptions)
            throws AzureExecutionException {
        final List<String> subsIdList = subscriptions.stream().map(Subscription::subscriptionId).collect(Collectors.toList());
        String targetSubscriptionId = StringUtils.firstNonBlank(this.subscriptionId, azureCredentialWrapper.getDefaultSubscriptionId());

        if (StringUtils.isBlank(targetSubscriptionId) && ArrayUtils.isNotEmpty(azureCredentialWrapper.getFilteredSubscriptionIds())) {
            final Collection<String> filteredSubscriptions = StringListUtils.intersectIgnoreCase(subsIdList,
                    Arrays.asList(azureCredentialWrapper.getFilteredSubscriptionIds()));
            if (filteredSubscriptions.size() == 1) {
                targetSubscriptionId = filteredSubscriptions.iterator().next();
            }
        }
        if (StringUtils.isBlank(targetSubscriptionId)) {
            return selectSubscription(subscriptions.toArray(new Subscription[0]));
        }
        return targetSubscriptionId;
    }

    protected String selectSubscription(Subscription[] subscriptions) throws AzureExecutionException {
        if (subscriptions.length == 0) {
            throw new AzureExecutionException("Cannot find any subscriptions in current account.");
        }
        if (subscriptions.length == 1) {
            Log.info(String.format("There is only one subscription '%s' in your account, will use it automatically.",
                    com.microsoft.azure.common.utils.TextUtils.blue(SubscriptionOption2.getSubscriptionName(subscriptions[0]))));
            return subscriptions[0].subscriptionId();
        }
        final List<SubscriptionOption2> wrapSubs = Arrays.stream(subscriptions).map(t -> new SubscriptionOption2(t))
                .sorted()
                .collect(Collectors.toList());
        final SubscriptionOption2 defaultValue = wrapSubs.stream().findFirst().orElse(null);
        final TextIO textIO = TextIoFactory.getTextIO();
        final SubscriptionOption2 subscriptionOptionSelected = new CustomTextIoStringListReader<SubscriptionOption2>(() -> textIO.getTextTerminal(), null)
                .withCustomPrompt(String.format("Please choose a subscription%s: ",
                        highlightDefaultValue(defaultValue == null ? null : defaultValue.getSubscriptionName())))
                .withNumberedPossibleValues(wrapSubs).withDefaultValue(defaultValue).read("Available subscriptions:");
        if (subscriptionOptionSelected == null) {
            throw new AzureExecutionException("You must select a subscription.");
        }
        return subscriptionOptionSelected.getSubscription().subscriptionId();
    }

    private static void checkSubscription(List<Subscription> subscriptions, String targetSubscriptionId) throws AzureLoginException {
        if (StringUtils.isEmpty(targetSubscriptionId)) {
            Log.warn(SUBSCRIPTION_NOT_SPECIFIED);
            return;
        }
        final Optional<Subscription> optionalSubscription = subscriptions.stream()
                .filter(subscription -> StringUtils.equals(subscription.subscriptionId(), targetSubscriptionId))
                .findAny();
        if (!optionalSubscription.isPresent()) {
            throw new AzureLoginException(String.format(SUBSCRIPTION_NOT_FOUND, targetSubscriptionId));
        }
    }

    private AzureEnvironment convertEnvironment(com.microsoft.azure.AzureEnvironment environment) {
        return AzureEnvironment.knownEnvironments().stream()
                .filter(env -> StringUtils.equals(environment.managementEndpoint(), env.getManagementEndpoint()))
                .findFirst().orElse(null);
    }

    @Override
    public String getDeploymentStagingDirectoryPath() {
        if (stagingDirectory == null) {
            synchronized (this) {
                if (stagingDirectory == null) {
                    final String outputFolder = this.getPluginName().replaceAll(MAVEN_PLUGIN_POSTFIX, "");
                    final String stagingDirectoryPath = Paths.get(this.getBuildDirectoryAbsolutePath(),
                            outputFolder, String.format("%s-%s", this.getAppName(), UUID.randomUUID().toString())
                    ).toString();
                    stagingDirectory = new File(stagingDirectoryPath);
                    // If staging directory doesn't exist, create one and delete it on exit
                    if (!stagingDirectory.exists()) {
                        stagingDirectory.mkdirs();
                    }
                }
            }
        }
        return stagingDirectory.getPath();
    }
    //endregion
}
