package com.netflix.asgard;

import java.util.Collection;
import java.util.List;

import groovyx.net.http.RESTClient;
import groovyx.net.http.ContentType;

import org.springframework.beans.factory.InitializingBean;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.AvailabilityZoneMessage
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState
import com.amazonaws.services.ec2.model.Monitoring
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.Tag
import com.netflix.asgard.model.AutoScalingProcessType;


public class OpenStackService implements InitializingBean {
    
    def apiInterceptorService
    def configService

    String restBase
    int tokenPort
    int imagePort
    int asgPort // TODO: Better name
    int heatPort

    String accessTokenTenantId
    String accessToken
    
    void afterPropertiesSet() {
        apiInterceptorService.setupServiceInterceptor(AwsEc2Service, OpenStackService, this, awsEc2ServiceShouldInterceptInvokeMethod)
        apiInterceptorService.setupServiceInterceptor(AwsAutoScalingService, OpenStackService, this, awsAutoScalingServiceShouldInterceptInvokeMethod)
        apiInterceptorService.setupServiceInterceptor(AwsLoadBalancerService, OpenStackService, this, defaultShouldInterceptInvokeMethod)

        restBase = configService.grailsApplication.config.openstack.apiHostname
        tokenPort = configService.grailsApplication.config.openstack.tokenPort
        imagePort = configService.grailsApplication.config.openstack.imagePort
        asgPort = configService.grailsApplication.config.openstack.asgPort
        heatPort = configService.grailsApplication.config.openstack.heatPort
    }

    def defaultShouldInterceptInvokeMethod = {name, args ->
        def shouldHandle = false
        if ((args!= null) && (args.length > 0) && (args[0] instanceof Region)) {
            def region = args[0] as Region
            if (region == Region.OPENSTACK_1) {
                shouldHandle = true
            }
        }
        return shouldHandle
    }

    def shouldHandleUserContext = {name, args, methodList ->
        def shouldHandle = false
        if ((args!= null) && (args.length > 0) && (args[0] instanceof UserContext)) {
            def userContext = args[0] as UserContext
            def region = userContext.region
            if (region == Region.OPENSTACK_1) {
                if (name in methodList) {
                    shouldHandle = true
                }
            }
        }
        return shouldHandle
    }
    
    def awsEc2ServiceInterceptNames = ["getImage"]
    def awsEc2ServiceShouldInterceptInvokeMethod = {name, args ->
        def shouldHandle = defaultShouldInterceptInvokeMethod(name,args) ||
        shouldHandleUserContext(name, args, awsEc2ServiceInterceptNames)
        return shouldHandle
    }


    def awsAutoScalingServiceInterceptNames = []
    def awsAutoScalingServiceShouldInterceptInvokeMethod = {name, args ->
        def shouldHandle = defaultShouldInterceptInvokeMethod(name,args) ||
        shouldHandleUserContext(name, args, awsAutoScalingServiceInterceptNames)
        return shouldHandle
    }

    public initialLogin() {
        // TODO:  Make the code smarter to just login
        log.debug 'logging into openstack'
        def restClient = new RESTClient('http://'+restBase+':'+tokenPort)
        openStackLogin(restClient)
    }
    
    def openStackLogin(RESTClient restClient) {
        def payload = "{\"auth\": {\"tenantName\": \"admin\", \"passwordCredentials\": {\"username\": \"admin\", \"password\": \"admin\"}}}"
        
        def resp = restClient.post(
            path : "/v2.0/tokens",
            body : payload,
            requestContentType : ContentType.JSON)   { resp, data ->
                log.debug 'after response to openstack login ...'
                log.info 'data return = ' + data
                assert resp.status == 200
                
                accessTokenTenantId = data.access.token.tenant.id
                accessToken = data.access.token.id
                
                log.debug("Result of login to ASG Controller = ${data}")
        }
    }
    
    def setAuthHeader(RESTClient restClient) {
        restClient.defaultRequestHeaders['X-Auth-Token'] = accessToken
        
        log.debug("Setting authorization header: ${restClient.defaultRequestHeaders}")
    }


    private List<Image> retrieveImages(Region region) {
        def restClient = new RESTClient('http://'+restBase+':'+imagePort)
        setAuthHeader(restClient)
        List <Image> images = []
        restClient.get(path: '/v1/images/detail') { response, json ->
            json.images.each { image ->
              def i = new Image()
              i.setName(image.name)
              i.setImageId(image.id)
              i.setImageLocation('unknown location')
              images.add(i)
              log.warn i.name
            }
        }
        images
    }
    
    private List<Instance> retrieveInstances(Region region) {
        def restClient = new RESTClient('http://'+restBase+':'+asgPort)
        setAuthHeader(restClient)
        List<Instance> instances = []
        restClient.get(path: '/v2/' + accessTokenTenantId + '/servers/detail') { response, json ->
            json.servers.each { server ->
              log.warn server
              def instance = new Instance(
                  instanceId: server.id,
                  imageId: server.image.id,
                  instanceType: "Open Stack Instance",
                  launchTime: new Date(),//ISODateTimeFormat.dateTimeParser().parseDateTime(inspectInfo.State.StartedAt).toDate(),
                  publicIpAddress : '', //inspectInfo.NetworkSettings.IPAddress,
                  privateIpAddress : '', //inspectInfo.NetworkSettings.IPAddress,
                  ).withState(
                      new InstanceState(code: 80, name: "Running")
                  ).withPlacement(
                      new Placement(availabilityZone: 'TBD', groupName: '', tenancy: 'default')
                  ).withTags(
                      [ new Tag(key: 'Name', value: 'TBD'/*"${inspectInfo.Name}"*/) ]
                  ).withMonitoring(new Monitoring(state : 'disabled'))
              instances.add(instance)
            }
            log.warn response
            log.warn json
        }
        instances
    }

    private List<AvailabilityZone> retrieveAvailabilityZones(Region region) {
        def restClient = new RESTClient('http://'+restBase+':'+asgPort)
        setAuthHeader(restClient)
        def List<AvailabilityZone> zones = []
        restClient.get(path: '/v2/' + accessTokenTenantId + '/os-availability-zone/detail') { response, json ->
            log.warn json
            zones = json.availabilityZoneInfo.collect { azInfo ->
                def AvailabilityZone az = new AvailabilityZone(zoneName : azInfo.zoneName, state : azInfo.zoneState.available == 'true' ? 'available' : 'unknown', regionName : Region.OPENSTACK_1)
                def AvailabilityZoneMessage message = new AvailabilityZoneMessage(message: 'TBD')
                az.setMessages([message])
                az
            }
        }
        zones
    }
    
    private List<AutoScalingGroup> retrieveAutoScalingGroups(Region region) {
        def restClient = new RESTClient('http://'+restBase+':'+heatPort)
        setAuthHeader(restClient)
        List<AutoScalingGroup> groups = []
        restClient.get(
            path: '/v1/' + accessTokenTenantId + '/stacks'
            ) { response, json ->
            log.warn 'response = ' + response;
            groups = json.stacks.collect { stack ->
                //log.debug("retrieved autoScalingGroup JSON from controller => ${asgJson}")
                def newAsg = new AutoScalingGroup()
                newAsg.setAutoScalingGroupName(stack.stack_name)
                newAsg.setLaunchConfigurationName("lc-TBD")
                newAsg.setMinSize(1)
                newAsg.setMaxSize(1)
                newAsg.setDesiredCapacity(1)
                //log.debug("retrieved autoScalingGroup JSON from controller => ${asgJson}")
                newAsg
            }//.sort({ a, b -> dtp.parseDateTime(a.createDate) <=> dtp.parseDateTime(b.createDate) } as Comparator)
        }
        groups
    }
    
    CreateAutoScalingGroupResult createLaunchConfigAndAutoScalingGroup(UserContext userContext,
            AutoScalingGroup groupTemplate, LaunchConfiguration launchConfigTemplate,
            Collection<AutoScalingProcessType> suspendedProcesses, boolean enableChaosMonkey = false,
            Task existingTask = null) {
            
        String networkId = getNetworkIdForExtNet();

//        CreateAutoScalingGroupResult result = new CreateAutoScalingGroupResult()
//        String groupName = groupTemplate.autoScalingGroupName
//        //String launchConfigName = Relationships.buildLaunchConfigurationName(groupName)
//        String msg = "Create Auto Scaling Group '${groupName}' in Docker local region."
//        //Subnets subnets = null//awsEc2Service.getSubnets(userContext)
//
//        AutoScalingGroupBeanOptions groupOptions = AutoScalingGroupBeanOptions.from(groupTemplate, subnets)
//        groupOptions.launchConfigurationName = launchConfigName
//        groupOptions.suspendedProcesses = suspendedProcesses
//
//        LaunchConfigurationBeanOptions launchConfig = LaunchConfigurationBeanOptions.from(launchConfigTemplate)
//        launchConfig.launchConfigurationName = launchConfigName
//
//        // Create the LaunchConfig
//        createLaunchConfiguration(userContext, launchConfig)
//        result.launchConfigName = launchConfigName
//        result.autoScalingGroupName = groupName
//        result.launchConfigCreated = true // should exist in ASG Controller now.
//
//        // Create the AutoScalingGroup next
//        createAutoScalingGroupForDocker(userContext, groupOptions)
//        // Start the AutoScalingGroup
//
//        result.autoScalingGroupCreated = true
//
//        startAutoScalingGroup(userContext, groupName)

        //          if (result.autoScalingGroupCreated && enableChaosMonkey) {
        //              String cluster = Relationships.clusterFromGroupName(groupTemplate.autoScalingGroupName)
        //              task.log("Enabling Chaos Monkey for ${cluster}.")
        //              Region region = userContext.region
        //              result.cloudReadyUnavailable = !cloudReadyService.enableChaosMonkeyForCluster(region, cluster)
        //          }

        result
    }
            
    public String getNetworkIdForExtNet() {
        def restClient = new RESTClient('http://'+restBase+':'+netPort)
        setAuthHeader(restClient)
        restClient.get(
            path: '/v2/' + accessTokenTenantId + '/networks.json'
            ) { response, json ->
            log.warn 'response = ' + response;
        }
        'id'
    }
}
