package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.rds.*;
import software.constructs.Construct;

import java.util.Collections;

public class RdsStack extends Stack {
    public RdsStack(final Construct scope, final String id, Vpc vpc) {
        this(scope, id, null, vpc);
    }

    public RdsStack(final Construct scope, final String id, final StackProps props, Vpc vpc) {
        super(scope, id, props);

        CfnParameter databasePassword = CfnParameter.Builder.create(this, "databasePassword")
                .type("String")
                .description("The RDS instance password")
                .build();

        ISecurityGroup iSecurityGroup = SecurityGroup.fromSecurityGroupId(this, id, vpc.getVpcDefaultSecurityGroup());
        iSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(3306));

        DatabaseInstance databaseInstance = DatabaseInstance.Builder
                .create(this, "Rds01")
                .instanceIdentifier("aws-project01-db")
                .engine(DatabaseInstanceEngine.mysql(MySqlInstanceEngineProps.builder()
                        .version(MysqlEngineVersion.VER_5_7)
                        .build()))
                .vpc(vpc)
                .credentials(Credentials.fromUsername(
                        "admin",
                        CredentialsFromUsernameOptions.builder()
                                .password(SecretValue.unsafePlainText(databasePassword.getValueAsString()))
                                .build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE2, InstanceSize.MICRO))//tamanho da instância
                .multiAz(false)// aqui diz que a instancia não deve ficar em várias zonas de disponibilidade
                .allocatedStorage(10)//tamanho do disco em 10 gigas
                .securityGroups(Collections.singletonList(iSecurityGroup))//definindo o security group definido anteriormente que já tem a porta aberta
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(vpc.getPublicSubnets())//aqui está pública porque fiz a configuração de criar a VPC sem a utilização de NatGateway
                        .build())
                .build();


        CfnOutput.Builder.create(this, "rds-endpoint")//definindo e expondo o endpoint de acesso ao banco de dados
                .exportName("rds-endpoint")
                .value(databaseInstance.getDbInstanceEndpointAddress())
                .build();

        CfnOutput.Builder.create(this, "rds-password")//definindo e expondo a senha de acesso ao banco de dados
                .exportName("rds-password")
                .value(databasePassword.getValueAsString())
                .build();
    }
}
