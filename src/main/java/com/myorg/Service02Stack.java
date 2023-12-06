package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.events.targets.SnsTopic;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.Map;

public class Service02Stack extends Stack {

    public Service02Stack(final Construct scope, final String id, Cluster cluster, SnsTopic productEventsTopic, Table productEventDdb) {

        this(scope, id, null, cluster, productEventsTopic, productEventDdb);
    }

    public Service02Stack(final Construct scope, final String id, final StackProps props, Cluster cluster, SnsTopic productEventsTopic, Table productEventDdb) {
        super(scope, id, props);

        Queue productEventsDlq = Queue.Builder
                .create(this, "ProductEventsDlq")
                .enforceSsl(false)
                .encryption(QueueEncryption.UNENCRYPTED)
                .queueName("product-events-dlq")
                .build();

        DeadLetterQueue deadLetterQueue = DeadLetterQueue.builder()
                .queue(productEventsDlq)
                .maxReceiveCount(3) //qtd de tentativas de tratamento da mensagem antes de enviar para dlq
                .build();


        Queue productEventsQueue = Queue.Builder
                .create(this, "ProductEvents")
                .enforceSsl(false)
                .encryption(QueueEncryption.UNENCRYPTED)
                .queueName("product-events")
                .deadLetterQueue(deadLetterQueue)
                .build();

        SqsSubscription sqsSubscription = SqsSubscription.Builder.create(productEventsQueue).build();
        productEventsTopic.getTopic().addSubscription(sqsSubscription);

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("AWS_REGION", "us-east-1");
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_EVENTS_NAME", productEventsQueue.getQueueName());

        ApplicationLoadBalancedFargateService service02 = ApplicationLoadBalancedFargateService.Builder
                .create(this, "ALB02")
                .serviceName("service-02")
                .cluster(cluster)
                .cpu(512)
                .desiredCount(2)// quantidade de instâncias
                .listenerPort(9090)
                .memoryLimitMiB(1024)
                .assignPublicIp(true)//fazer essa configuração para o caso de ter definido o natGateways como 0 na VpcStack
                .taskImageOptions(ApplicationLoadBalancedTaskImageOptions
                        .builder()
                        .containerName("aws_project02")
                        .image(ContainerImage
                                .fromRegistry("name_image_docker2"))
                        .containerPort(9090)
                        .logDriver(LogDriver.awsLogs(AwsLogDriverProps.builder()//configura para que os logs aparecem no cloud watch
                                .logGroup(LogGroup.Builder
                                        .create(this, "Service02LogGroup")
                                        .logGroupName("Service02")
                                        .removalPolicy(RemovalPolicy.DESTROY)// essa política apaga todos os logs quando apagar o APL
                                        .build())
                                .streamPrefix("Service02").build()))
                        .environment(envVariables)
                        .build()

                ).publicLoadBalancer(true).build();

        service02.getTargetGroup().configureHealthCheck(new HealthCheck.Builder().path("/actuator/health").port("9090").healthyHttpCodes("200").build());

        //configuração do auto scaling
        ScalableTaskCount scalableTaskCount = service02.getService().autoScaleTaskCount(EnableScalingProps.builder().minCapacity(2)//nesse caso não pode ter menos de 2 instâncias
                .maxCapacity(4)//ness caso não pode ter mais de 4 instâncias
                .build());

        scalableTaskCount.scaleOnCpuUtilization("Service02AutoScaling", CpuUtilizationScalingProps.builder().targetUtilizationPercent(50)// nesse caso se o consume de cpu ultrapassar os 50%...
                .scaleInCooldown(Duration.seconds(60))// ... num intervalo de 60s então ele cria uma nova instânica no limite de 4 instância conforme definido acima
                .scaleOutCooldown(Duration.seconds(60))// período de análise para destruir as instâncias extras criadas p/ quando tiver o consume médio abaixo de cpu abaixo de 50%
                .build());

        productEventsQueue.grantConsumeMessages(service02.getTaskDefinition().getTaskRole());//permissões de acesso
        productEventDdb.grantReadWriteData(service02.getTaskDefinition().getTaskRole());//permissões de acesso
    }
}

// para fazer deploy do banco de dados: cdk deploy --parameters Rds:databasePassword=qualquer12345 Rds Service01
//para achar o endpoint vai em cloudformation e procura a stack Rds e em Parameters tem a senha como
//parâmetros de entrada e em Outputs tem o endpoint.
//também pode procurar diretamente pelo serviço do Rds

