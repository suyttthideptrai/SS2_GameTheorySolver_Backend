package com.example.SS2_Backend.model.onetomany;

import com.example.SS2_Backend.model.onetomany.Requirement.Requirement;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.util.*;

@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class PreferenceProvider {

    final IndividualList individuals;
    final int totalIndividuals;
    final int providerCount;
    final int propertiesPerIndividual;
    Expression providerEvaluateExpression;
    Expression consumerEvaluateExpression;
    Map<String, Set<Integer>> providerVariables;
    Map<String, Set<Integer>> consumerVariables;

    public PreferenceProvider(IndividualList individuals) {
        this.individuals = individuals;
        this.totalIndividuals = individuals.getTotalIndividuals();
        this.providerCount = individuals.getProviderCount();
        this.propertiesPerIndividual = individuals.getPropertiesPerIndividual();
    }

    public void setProviderEvaluateFunction(String evaluateFunction) {
        if (providerEvaluateExpression != null) return;
        this.providerVariables = filterVariable(evaluateFunction);
        this.providerEvaluateExpression = new ExpressionBuilder(evaluateFunction)
                .variables(convertMapToSet(providerVariables))
                .build();
    }

    public void setConsumerEvaluateFunction(String evaluateFunction) {
        if (consumerEvaluateExpression != null) return;
        this.consumerVariables = filterVariable(evaluateFunction);
        this.consumerEvaluateExpression = new ExpressionBuilder(evaluateFunction)
                .variables(convertMapToSet(consumerVariables))
                .build();
    }

    public Set<String> convertMapToSet(Map<String, Set<Integer>> varMap) {
        Set<String> resultSet = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> entry : varMap.entrySet()) {
            String variable = entry.getKey();
            for (Integer value : entry.getValue()) {
                resultSet.add(variable + value.toString());
            }
        }
        return resultSet;
    }

    public Map<String, Set<Integer>> filterVariable(String evaluateFunction) {
        Map<String, Set<Integer>> variables = new HashMap<>();
        for (int c = 0; c < evaluateFunction.length(); c++) {
            char ch = evaluateFunction.charAt(c);
            switch (ch) {
                case 'P':
                case 'W':
                case 'R': // requirement for other set
                    String prefix = String.valueOf(ch);
                    Optional<Integer> nextIdx = getNextIndexToken(evaluateFunction, c);
                    if (nextIdx.isPresent()) {
                        int idx = nextIdx.get();
                        variables.compute(prefix, (key, value) -> {
                            if (value == null) {
                                Set<Integer> set = new HashSet<>();
                                set.add(idx);
                                return set;
                            } else {
                                value.add(idx);
                                return value;
                            }
                        });
                    } else {
                        throw new IllegalArgumentException("Invalid expression after: " + prefix);
                    }
            }
        }
        return variables;
    }

    public Optional<Integer> getNextIndexToken(String evaluateFunction, int currentIndex) {
        int nextIndex = currentIndex + 1;
        while (nextIndex < evaluateFunction.length() &&
                Character.isDigit(evaluateFunction.charAt(nextIndex))) {
            nextIndex++;
        }
        if (nextIndex == currentIndex + 1) {
            return Optional.empty();
        }
        String subString = evaluateFunction.substring(currentIndex + 1, nextIndex);
        int idx = Integer.parseInt(subString);
        return Optional.of(idx);
    }

    public Map<String, Double> getVariableValues(int indexOfEvaluator, int indexOfEvaluated, boolean isProviderEvaluating) {
        Map<String, Double> variablesValues = new HashMap<>();
        Map<String, Set<Integer>> variables = isProviderEvaluating ? providerVariables : consumerVariables;

        for (Map.Entry<String, Set<Integer>> entry : variables.entrySet()) {
            String key = entry.getKey();
            Set<Integer> values = entry.getValue();
            switch (key) {
                case "P":
                    for (Integer value : values) {
                        double val = individuals.getAttributeValueOf(indexOfEvaluated, value - 1);
                        variablesValues.put(key + value, val);
                    }
                    break;
                case "W":
                    for (Integer value : values) {
                        double val = individuals.getAttributeWeightOf(indexOfEvaluator, value - 1);
                        variablesValues.put(key + value, val);
                    }
                    break;
                case "R":
                    for (Integer value : values) {
                        double val = individuals.getRequirementOf(indexOfEvaluator, value - 1)
                                .getValueForFunction();
                        variablesValues.put(key + value, val);
                    }
                    break;
                default:
                    variablesValues.put(key, 0.0);
            }
        }
        return variablesValues;
    }

    public PreferenceList getPreferenceListByFunction(int indexOfIndividual) {
        boolean isProvider = individuals.getRoleOfParticipant(indexOfIndividual) == 0;
        Expression evaluateExpression = isProvider ? providerEvaluateExpression : consumerEvaluateExpression;

        int otherSetSize = isProvider ? totalIndividuals - providerCount : providerCount;
        PreferenceList preferenceList = new PreferenceList(otherSetSize, isProvider ? providerCount : 0);

        if (evaluateExpression == null) {
            return getPreferenceListByDefault(indexOfIndividual);
        }

        for (int i = 0; i < totalIndividuals; i++) {
            if (individuals.getRoleOfParticipant(i) != individuals.getRoleOfParticipant(indexOfIndividual)) {
                evaluateExpression.setVariables(getVariableValues(indexOfIndividual, i, isProvider));
                double score = evaluateExpression.evaluate();
                preferenceList.add(score, i);
            }
        }

        preferenceList.sortPreferences();
        return preferenceList;
    }

    public PreferenceList getPreferenceListByDefault(int indexOfIndividual) {
        boolean isProvider = individuals.getRoleOfParticipant(indexOfIndividual) == 0;
        int otherSetSize = isProvider ? totalIndividuals - providerCount : providerCount;
        PreferenceList preferenceList = new PreferenceList(otherSetSize, isProvider ? providerCount : 0);

        for (int i = 0; i < totalIndividuals; i++) {
            if (individuals.getRoleOfParticipant(i) != individuals.getRoleOfParticipant(indexOfIndividual)) {
                double totalScore = 0;
                for (int j = 0; j < propertiesPerIndividual; j++) {
                    double propertyValue = individuals.getAttributeValueOf(i, j);
                    Requirement requirement = individuals.getRequirementOf(indexOfIndividual, j);
                    double propertyWeight = individuals.getAttributeWeightOf(indexOfIndividual, j);
                    totalScore += getDefaultScaling(requirement, propertyValue) * propertyWeight;
                }
                preferenceList.add(totalScore, i);
            }
        }

        preferenceList.sortPreferences();
        return preferenceList;
    }

    public static double getDefaultScaling(Requirement requirement, double propertyValue) {
        int type = requirement.getType();
        // Case: Scale
        if (type == 0) {
            int targetValue = requirement.getTargetValue();
            if (propertyValue < 0 || propertyValue > 10) {
                return 0.0;
            } else {
                double Distance = Math.abs(propertyValue - targetValue);
                if (Distance > 7) return 0;
                if (Distance > 5) return 1;
                return (10 - Distance) / 10 + 1;
            }
            //Case: 1 Bound
        } else if (type == 1) {
            double Bound = requirement.getBound();
            String expression = requirement.getExpression();
            if (Objects.equals(expression, "++")) {
                if (propertyValue < Bound) {
                    return 0.0;
                } else {
                    if (Bound == 0) return 2.0;
                    double distance = Math.abs(propertyValue - Bound);
                    return (Bound + distance) / Bound;
                }
            } else {
                if (propertyValue > Bound) {
                    return 0.0;
                } else {
                    if (Bound == 0) return 2.0;
                    double distance = Math.abs(propertyValue - Bound);
                    return (Bound + distance) / Bound;
                }
            }
            //Case: 2 Bounds
        } else {
            double lowerBound = requirement.getLowerBound();
            double upperBound = requirement.getUpperBound();
            if (propertyValue < lowerBound || propertyValue > upperBound ||
                    lowerBound == upperBound) {
                return 0.0;
            } else {
                double diff = Math.abs(upperBound - lowerBound) / 2;
                double distance = Math.abs(((lowerBound + upperBound) / 2) - propertyValue);
                return (diff - distance) / diff + 1;
            }
        }
    }
}