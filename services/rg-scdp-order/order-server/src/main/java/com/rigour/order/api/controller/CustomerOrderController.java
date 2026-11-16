package com.rigour.order.api.controller;
import com.rigour.order.api.v1.CustomerOrders;
import com.rigour.order.application.service.sales.CustomerOrderService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/orders/customers")
public class CustomerOrderController {
 private final CustomerOrderService service;
 public CustomerOrderController(CustomerOrderService service){this.service=service;}
 @GetMapping("/{id}") public ApiResponse<CustomerOrders> read(@PathVariable String id,@RequestParam(defaultValue="false") boolean unpaid,@RequestParam(defaultValue="0") int begin,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.read(id,unpaid,begin,size));}
}
