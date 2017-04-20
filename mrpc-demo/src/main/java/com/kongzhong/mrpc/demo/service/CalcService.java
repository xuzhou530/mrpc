package com.kongzhong.mrpc.demo.service;

import com.kongzhong.mrpc.annotation.GET;
import com.kongzhong.mrpc.annotation.POST;
import com.kongzhong.mrpc.demo.model.Person;

/**
 * @author biezhi
 *         2017/4/19
 */
public interface CalcService {

    int add(int a, int b);

    @GET("/hello")
    String hello(String name);

    @POST("/person")
    boolean savePerson(Person person, Integer age);

}