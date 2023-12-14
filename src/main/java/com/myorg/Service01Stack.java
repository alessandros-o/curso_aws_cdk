package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.events.targets.SnsTopic;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.Map;

public class Service01Stack extends Stack {
    public Service01Stack(final Construct scope, final String id, Cluster cluster, SnsTopic productEventsTopic, Bucket invoiceBucket, Queue invoiceQueue) {

        this(scope, id, null, cluster, productEventsTopic, invoiceBucket, invoiceQueue);
    }

    public Service01Stack(final Construct scope, final String id, final StackProps props, Cluster cluster,
                          SnsTopic productEventsTopic, Bucket invoiceBucket, Queue invoiceQueue) {
        super(scope, id, props);

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SPRING_DATASOURCE_URL", "jdbc:mariadb://" + Fn.importValue("rds-endpoint")
        + ":3306/aws_project01?createDatabaseIfNotExist=true");
        envVariables.put("SPRING_DATASOURCE_USERNAME", "admin");
        envVariables.put("SPRING_DATASOURCE_PASSWORD", Fn.importValue("rds-password"));
        envVariables.put("AWS_REGION", "us-east-1");
        envVariables.put("AWS_SNS_TOPIC_PRODUCT_EVENTS_ARN", productEventsTopic.getTopic().getTopicArn());
        envVariables.put("AWS_S3_BUCKET_INVOICE_NAME", invoiceBucket.getBucketName());
        envVariables.put("AWS_SQS_QUEUE_INVOICE_EVENTS_NAME", invoiceQueue.getQueueName());

        ApplicationLoadBalancedFargateService service01 = ApplicationLoadBalancedFargateService
                .Builder
                .create(this, "ALB01")
                .serviceName("service-01")
                .cluster(cluster)
                .cpu(512)
                .desiredCount(2)// quantidade de instâncias
                .listenerPort(8080)
                .memoryLimitMiB(1024)
                .assignPublicIp(true)//fazer essa configuração para o caso de ter definido o natGateways como 0 na VpcStack
                .taskImageOptions(
                        ApplicationLoadBalancedTaskImageOptions.builder()
                                .containerName("aws_project01")
                                .image(ContainerImage.fromRegistry("name_image_docker"))
                                .containerPort(8080)
                                .logDriver(
                                        LogDriver.awsLogs(
                                                AwsLogDriverProps.builder()//configura para que os logs aparecem no cloud watch
                                                        .logGroup(
                                                                LogGroup.Builder.create(this, "Service01LogGroup")
                                                                        .logGroupName("Service01")
                                                                        .removalPolicy(RemovalPolicy.DESTROY)// essa política apaga todos os logs quando apagar o APL
                                                                        .build()
                                                        ).streamPrefix("Service01")
                                                        .build()))
                                .environment(envVariables)
                                .build()

                ).publicLoadBalancer(true)
                .build();

        service01.getTargetGroup().configureHealthCheck(new HealthCheck.Builder()
                .path("/actuator/health")
                .port("8080")
                .healthyHttpCodes("200")
                .build());

        //configuração do auto scaling
        ScalableTaskCount scalableTaskCount = service01.getService().autoScaleTaskCount(EnableScalingProps.builder()
                .minCapacity(2)//nesse caso não pode ter menos de 2 instâncias
                .maxCapacity(4)//ness caso não pode ter mais de 4 instâncias
                .build());

        scalableTaskCount.scaleOnCpuUtilization("Service01AutoScaling", CpuUtilizationScalingProps.builder()
                .targetUtilizationPercent(50)// nesse caso se o consume de cpu ultrapassar os 50%...
                .scaleInCooldown(Duration.seconds(60))// ... num intervalo de 60s então ele cria uma nova instânica no limite de 4 instância conforme definido acima
                .scaleOutCooldown(Duration.seconds(60))// período de análise para destruir as instâncias extras criadas p/ quando tiver o consume médio abaixo de cpu abaixo de 50%
                .build());


        //permissões
        productEventsTopic.getTopic().grantPublish(service01.getTaskDefinition().getTaskRole());
        invoiceQueue.grantConsumeMessages(service01.getTaskDefinition().getTaskRole());
        invoiceBucket.grantReadWrite(service01.getTaskDefinition().getTaskRole());
    }
}

// para fazer deploy do banco de dados: cdk deploy --parameters Rds:databasePassword=qualquer12345 Rds Service01
//para achar o endpoint vai em cloudformation e procura a stack Rds e em Parameters tem a senha como
//parâmetros de entrada e em Outputs tem o endpoint.
//também pode procurar diretamente pelo serviço do Rds