package com.idata.profile.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("\u4f60\u662f\u4e00\u4e2a\u4e13\u4e1a\u7684\u8ba4\u77e5\u64cd\u63a7\u5206\u6790\u52a9\u624b\uff0c"
                        + "\u5e2e\u52a9\u5206\u6790\u5e08\u7406\u89e3\u793e\u4ea4\u5a92\u4f53\u4e0a\u7684\u4fe1\u606f\u64cd\u63a7\u884c\u4e3a\u3002"
                        + "\u8bf7\u7528\u4e2d\u6587\u56de\u7b54\uff0c\u5206\u6790\u8981\u7b80\u6d01\u4e13\u4e1a\u3002")
                .build();
    }
}
