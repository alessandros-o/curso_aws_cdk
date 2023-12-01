package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.events.targets.SnsTopic;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

public class SnsStack extends Stack {

    private final SnsTopic productEventsTopic;

    public SnsStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public SnsStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        productEventsTopic = SnsTopic.Builder
                .create(Topic
                        .Builder
                        .create(this, "ProductEventsTopic")
                        .build())
                .build();

        //para inscrever um email para receber as mensagens dos eventos
        productEventsTopic.getTopic()
                .addSubscription(EmailSubscription.Builder.create("teste@teste.com")
                        .json(true)
                        .build());
    }

    public SnsTopic getProductEventTopic() {
        return productEventsTopic;
    }
}
