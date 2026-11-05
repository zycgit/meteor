/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;
import net.hasor.meteor.console.component.commands.action.*;
import net.hasor.meteor.console.component.commands.dto.directory.*;
import net.hasor.meteor.console.component.commands.dto.instance.InstanceInfoRequest;
import net.hasor.meteor.console.component.commands.dto.instance.SetInstanceBlockedRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.FetchMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.PutMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.metadata.RemoveMetadataRequest;
import net.hasor.meteor.console.component.commands.dto.rule.*;
import net.hasor.meteor.console.component.commands.dto.service.MembersRequest;
import net.hasor.meteor.console.component.commands.dto.service.ServiceInfoRequest;
import net.hasor.meteor.console.component.commands.dto.service.SetServiceEnabledRequest;

/** 注册中心管理操作及其请求、实现类型。 */
public enum CommandType {
    CLUSTER_INFO(ClusterInfoRequest.class, ClusterInfoRegistryCmd.class),
    UNITS(UnitsRequest.class, UnitsRegistryCmd.class),
    SERVICES(ServicesRequest.class, ServicesRegistryCmd.class),
    INSTANCES(InstancesRequest.class, InstancesRegistryCmd.class),
    APPLICATIONS(ApplicationsRequest.class, ApplicationsRegistryCmd.class),
    INSTANCE_INFO(InstanceInfoRequest.class, InstanceInfoRegistryCmd.class),
    SERVICE_INFO(ServiceInfoRequest.class, ServiceInfoRegistryCmd.class),
    MEMBERS(MembersRequest.class, MembersRegistryCmd.class),
    READ_RULE(ReadRuleRequest.class, ReadRuleRegistryCmd.class),
    VALIDATE_RULE(ValidateRuleRequest.class, ValidateRuleRegistryCmd.class),
    UPDATE_RULE(UpdateRuleRequest.class, UpdateRuleRegistryCmd.class),
    RESET_RULE(ResetRuleRequest.class, ResetRuleRegistryCmd.class),
    SET_RULE_ENABLED(SetRuleEnabledRequest.class, SetRuleEnabledRegistryCmd.class),
    SET_SERVICE_ENABLED(SetServiceEnabledRequest.class, SetServiceEnabledRegistryCmd.class),
    SET_INSTANCE_BLOCKED(SetInstanceBlockedRequest.class, SetInstanceBlockedRegistryCmd.class),
    FETCH_METADATA(FetchMetadataRequest.class, FetchMetadataRegistryCmd.class),
    PUT_METADATA(PutMetadataRequest.class, PutMetadataRegistryCmd.class),
    REMOVE_METADATA(RemoveMetadataRequest.class, RemoveMetadataRegistryCmd.class);

    private final Class<? extends CommandRequest<?>>     requestType;
    private final Class<? extends RegistryCommand<?, ?>> commandType;

    <R, Q extends CommandRequest<R>> CommandType(Class<Q> requestType, Class<? extends RegistryCommand<Q, R>> commandType) {
        this.requestType = requestType;
        this.commandType = commandType;
    }

    public Class<? extends CommandRequest<?>> getRequestType() {
        return this.requestType;
    }

    public Class<? extends RegistryCommand<?, ?>> getCommandType() {
        return this.commandType;
    }
}