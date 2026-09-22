package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssignmentSelectionPolicyTest {

    @Test
    void selectsHomeWhenAndExpressionRequiresSt() {
        assertEquals(AssignmentSelectionPolicy.Mode.HOME, AssignmentSelectionPolicy.fromExpression("""
                {"operator":"and","conditions":[
                  {"field":"AssignmentClass","operator":"eq","value":"ST"},
                  {"field":"Country","operator":"eq","value":"CN"}
                ]}
                """));
    }

    @Test
    void selectsPrimaryHostWhenExpressionRequiresGa() {
        assertEquals(AssignmentSelectionPolicy.Mode.HOST_PRIMARY, AssignmentSelectionPolicy.fromExpression("""
                {"operator":"and","conditions":[
                  {"field":"AssignmentClass","operator":"in","values":["GA"]},
                  {"field":"Country","operator":"eq","value":"SG"}
                ]}
                """));
    }

    @Test
    void keepsPrimaryWhenAssignmentConstraintIsOptionalOrAbsent() {
        assertEquals(AssignmentSelectionPolicy.Mode.PRIMARY, AssignmentSelectionPolicy.fromExpression("""
                {"operator":"or","conditions":[
                  {"field":"AssignmentClass","operator":"eq","value":"GA"},
                  {"field":"Country","operator":"eq","value":"CN"}
                ]}
                """));
        assertEquals(AssignmentSelectionPolicy.Mode.PRIMARY,
                AssignmentSelectionPolicy.fromExpression("{\"field\":\"Country\",\"operator\":\"eq\",\"value\":\"CN\"}"));
    }
}
