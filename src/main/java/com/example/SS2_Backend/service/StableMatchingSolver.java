package com.example.SS2_Backend.service;

import com.example.SS2_Backend.dto.request.StableMatchingProblemDTO;
import com.example.SS2_Backend.dto.response.Progress;
import com.example.SS2_Backend.dto.response.Response;
import com.example.SS2_Backend.model.GameSolutionInsights;
import com.example.SS2_Backend.model.StableMatching.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.moeaframework.Executor;
import org.moeaframework.core.*;
import org.moeaframework.core.spi.AlgorithmFactory;
import org.moeaframework.core.spi.ProviderNotFoundException;
import org.moeaframework.core.termination.MaxFunctionEvaluations;
import org.moeaframework.util.TypedProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor

public class StableMatchingSolver {
	private final SimpMessagingTemplate simpMessagingTemplate;

	private static final int RUN_COUNT_PER_ALGORITHM = 10; // for insight running, each algorithm will be run for 10 times
	public static ArrayList<Double> coupleFitnessList = new ArrayList<>();


	public ResponseEntity<Response> solveStableMatching(StableMatchingProblemDTO request) {

		try {
			StableMatchingProblem problem = new StableMatchingProblem();

			problem.setEvaluateFunctionForSet1(request.getEvaluateFunction()[0]);
			problem.setEvaluateFunctionForSet2(request.getEvaluateFunction()[1]);
			problem.setFitnessFunction(request.getFitnessFunction());
			problem.setPopulation(request.getIndividuals());
			problem.setAllPropertyNames(request.getAllPropertyNames());

			System.out.println("Load Problem...");
			System.out.println(problem);
			System.out.println("\nProblem loaded!");
			long startTime = System.currentTimeMillis();
			NondominatedPopulation results = solveProblem(
			    problem,
			    request.getAlgorithm(),
			    request.getPopulationSize(),
			    request.getGeneration(),
			    request.getMaxTime(),
			    request.getDistributedCores()
			);
			System.out.println(results);
			ArrayList<Individual> individualsList = request.getIndividuals();
			long endTime = System.currentTimeMillis();
			double runtime = ((double) (endTime - startTime) / 1000);
			runtime = (runtime * 1000.0);
			System.out.println("Runtime: " + runtime + " Millisecond(s).");
			String algorithm = request.getAlgorithm();
			assert results != null;
			MatchingSolution matchingSolution = formatSolution(algorithm, results, runtime);
			matchingSolution.setIndividuals(individualsList);
			System.out.println("RESPOND TO FRONT_END:");
			System.out.println(matchingSolution);
			return ResponseEntity.ok(
			    Response.builder()
			        .status(200)
			        .message("Solve stable matching problem successfully!")
			        .data(matchingSolution)
			        .build()
			);
		} catch (Exception e) {
			log.error("Error solving stable matching problem: {}", e.getMessage(), e);
			// Handle exceptions and return an error response
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			    .body(Response.builder()
			        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
			        .message("Error solving stable matching problem.")
			        .data(null)
			        .build());
		}
	}

	private MatchingSolution formatSolution(String algorithm, NondominatedPopulation result, double Runtime) {
		Solution solution = result.get(0);
		MatchingSolution matchingSolution = new MatchingSolution();
		double fitnessValue = solution.getObjective(0);
		Matches matches = (Matches) solution.getAttribute("matches");

		matchingSolution.setFitnessValue(-fitnessValue);
		matchingSolution.setMatches(matches);
		matchingSolution.setAlgorithm(algorithm);
		matchingSolution.setRuntime(Runtime);

		return matchingSolution;
	}


	private NondominatedPopulation solveProblem(StableMatchingProblem problem,
					    String algorithm,
					    int populationSize,
					    int generation,
					    int maxTime,
					    String distributedCores) {
		NondominatedPopulation result;
		if (algorithm == null) {
			algorithm = "PESA2";
		}
		if (distributedCores == null) {
			distributedCores = "all";
		}
		TypedProperties properties = new TypedProperties();
		properties.setInt("populationSize", populationSize);
		properties.setInt("maxTime", maxTime);
		TerminationCondition maxEval = new MaxFunctionEvaluations(generation * populationSize);
		try {
			if (distributedCores.equals("all")) {
				result = new Executor()
				    .withProblem(problem)
				    .withAlgorithm(algorithm)
				    .withMaxEvaluations(generation * populationSize)
				    .withTerminationCondition(maxEval)
				    .withProperties(properties)
				    .distributeOnAllCores()
				    .run();
			} else {
				int numberOfCores = Integer.parseInt(distributedCores);
				result = new Executor()
				    .withProblem(problem)
				    .withAlgorithm(algorithm)
				    .withMaxEvaluations(generation * populationSize)
				    .withTerminationCondition(maxEval)
				    .withProperties(properties)
				    .distributeOn(numberOfCores)
				    .run();
			}
			return result;
		} catch (Exception e) {
			log.error("Error solving the problem using MOEA framework: {}", e.getMessage(), e);
			return null;
		}

//        for (Solution solution : result) {
//            System.out.println("Randomized Individuals Input Order (by MOEA): " + solution.getVariable(0).toString());
//            Matches matches = (Matches) solution.getAttribute("matches");
//            System.out.println("Output Matches (by Gale Shapley):\n" + matches.toString());
//            System.out.println("Fitness Score: " + -solution.getObjective(0));
	}

	public ResponseEntity<Response> getProblemResultInsights(StableMatchingProblemDTO request, String sessionCode) {
//        log.info("Received request: " + request);
		String[] algorithms = {"NSGAII", "NSGAIII", "eMOEA", "PESA2", "VEGA", "MOEAD"};


		simpMessagingTemplate.convertAndSendToUser(sessionCode, "/progress", createProgressMessage("Initializing the problem..."));
		StableMatchingProblem problem = new StableMatchingProblem();
		problem.setEvaluateFunctionForSet1(request.getEvaluateFunction()[0]);
		problem.setEvaluateFunctionForSet2(request.getEvaluateFunction()[1]);
		problem.setPopulation(request.getIndividuals());
		problem.setAllPropertyNames(request.getAllPropertyNames());
		problem.setFitnessFunction(request.getFitnessFunction());

		MatchingSolutionInsights matchingSolutionInsights = initMatchingSolutionInsights(algorithms);

		int runCount = 1;
		int maxRunCount = algorithms.length * RUN_COUNT_PER_ALGORITHM;
		// solve the problem with different algorithms and then evaluate the performance of the algorithms
//        log.info("Start benchmarking the algorithms...");
		simpMessagingTemplate.convertAndSendToUser(sessionCode, "/progress", createProgressMessage("Start benchmarking the algorithms..."));

		for (String algorithm : algorithms) {
//            log.info("Running algorithm: " + algorithm + "...");
			for (int i = 0; i < RUN_COUNT_PER_ALGORITHM; i++) {
				System.out.println("Iteration: " + i);
				long start = System.currentTimeMillis();

				NondominatedPopulation results = solveProblem(
				    problem,
				    algorithm,
				    request.getPopulationSize(),
				    request.getGeneration(),
				    request.getMaxTime(),
				    request.getDistributedCores()
				);

				long end = System.currentTimeMillis();

				double runtime = (double) (end - start) / 1000;
				double fitnessValue = getFitnessValue(results);

				// send the progress to the client
				String message = "Algorithm " + algorithm + " finished iteration: #" + (i + 1) + "/" + RUN_COUNT_PER_ALGORITHM;
				Progress progress = createProgress(message, runtime, runCount, maxRunCount);
				System.out.println(progress);
				simpMessagingTemplate.convertAndSendToUser(sessionCode, "/progress", progress);
				runCount++;

				// add the fitness value and runtime to the insights
				matchingSolutionInsights.getFitnessValues().get(algorithm).add(-fitnessValue);
				matchingSolutionInsights.getRuntimes().get(algorithm).add(runtime);


			}

		}
//        log.info("Benchmarking finished!");
		simpMessagingTemplate.convertAndSendToUser(sessionCode, "/progress", createProgressMessage("Benchmarking finished!"));

		return ResponseEntity.ok(
		    Response.builder()
		        .status(200)
		        .message("Get problem result insights successfully!")
		        .data(matchingSolutionInsights)
		        .build()
		);
	}

	private MatchingSolutionInsights initMatchingSolutionInsights(String[] algorithms) {
		MatchingSolutionInsights matchingSolutionInsights = new MatchingSolutionInsights();
		Map<String, List<Double>> fitnessValueMap = new HashMap<>();
		Map<String, List<Double>> runtimeMap = new HashMap<>();

		matchingSolutionInsights.setFitnessValues(fitnessValueMap);
		matchingSolutionInsights.setRuntimes(runtimeMap);

		for (String algorithm : algorithms) {
			fitnessValueMap.put(algorithm, new ArrayList<>());
			runtimeMap.put(algorithm, new ArrayList<>());
		}

		return matchingSolutionInsights;
	}

	private Progress createProgressMessage(String message) {
		return Progress.builder()
		    .inProgress(false) // this object is just to send a message to the client, not to show the progress
		    .message(message)
		    .build();
	}

	private Progress createProgress(String message, Double runtime, Integer runCount, int maxRunCount) {
		int percent = runCount * 100 / maxRunCount;
		int minuteLeff = (int) Math.ceil(((maxRunCount - runCount) * runtime) / 60); // runtime is in seconds
		return Progress.builder()
		    .inProgress(true) // this object is just to send to the client to show the progress
		    .message(message)
		    .runtime(runtime)
		    .minuteLeft(minuteLeff)
		    .percentage(percent)
		    .build();
	}

	private double getFitnessValue(NondominatedPopulation result) {

		Solution solution = result.get(0);
		double fitnessValue = solution.getObjective(0);
		return fitnessValue;

	}
}
