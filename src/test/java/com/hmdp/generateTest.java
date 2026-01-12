package com.hmdp;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.SneakyThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@SpringBootTest
@AutoConfigureMockMvc
class generateTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper mapper;

    @Test
    @SneakyThrows
    @DisplayName("通过手机号注册用户，将token存储到Redis并输出到文件")
    void generateTokens() {
        List<String> phoneList = userService.lambdaQuery()
                .select(User::getPhone)
                .last("limit 1000")
                .list().stream().map(User::getPhone).collect(Collectors.toList());
        ExecutorService executorService = ThreadUtil.newExecutor(phoneList.size());
        List<String> tokenList = new CopyOnWriteArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(phoneList.size());
        phoneList.forEach(phone -> {
            executorService.execute(() -> {
                try {
                    // 发送验证码请求（验证码会存储在Redis中，出于安全考虑不会在响应中返回）
                    mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/code")
                                    .queryParam("phone", phone))
                            .andExpect(MockMvcResultMatchers.status().isOk());
                    
                    // 休眠一段时间确保验证码已存入Redis
                    Thread.sleep(100);
                    
                    // 从Redis获取正确的验证码
                    String code = stringRedisTemplate.opsForValue().get("login:code:" + phone);
                    if (code == null) {
                        // 如果Redis中没有验证码，则使用手机号哈希值生成确定性验证码
                        code = String.valueOf(Math.abs(phone.hashCode()) % 1000000);
                        if (code.length() < 6) {
                            code = String.format("%06d", Integer.parseInt(code));
                        }
                    }
                    LoginFormDTO formDTO = LoginFormDTO.builder()
                            .code(code)
                            .phone(phone)
                            .build();
                    String json = mapper.writeValueAsString(formDTO);
                    // token
                    String tokenJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/login").content(json).contentType(MediaType.APPLICATION_JSON))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();

                    Result result = mapper.readerFor(Result.class).readValue(tokenJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的token失败,json为“%s”", phone, json));
                    String token = result.getData() != null ? result.getData().toString() : null;
                    if (token != null) {
                        tokenList.add(token);
                    } else {
                        System.err.println("获取“" + phone + "”手机号的token失败，data为null");
                    }
                    countDownLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
        countDownLatch.await();
        executorService.shutdown();
        Assert.isTrue(tokenList.size() == phoneList.size());
        
        // 将token列表存储到Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + "batch_tokens", String.join(",", tokenList));
        
        // 将token输出到文件
        writeToTxt(tokenList, "\\tokens_output.txt");
        System.out.println("生成完成！共生成" + tokenList.size() + "个token");
    }

    private static void writeToTxt(List<String> list, String suffixPath) throws Exception {
        // 1. 创建文件
        File file = new File(System.getProperty("user.dir") + "\\src\\main\\resources" + suffixPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        // 2. 输出
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        for (String content : list) {
            bw.write(content);
            bw.newLine();
        }
        bw.close();
        System.out.println("写入完成！");
    }
}
