package edu.neu.cs6650.kv.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

  // Temporary smoke-test endpoint to confirm the app starts and request routing works.
  @GetMapping("/hello")
  public String hello() {
    return "hello";
  }
}
