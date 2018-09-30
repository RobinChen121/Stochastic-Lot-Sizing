package cash.strongconstraint;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import cash.strongconstraint.FindsCS.FindCCrieria;
import sdp.cash.CashRecursion;
import sdp.cash.CashRecursion.OptDirection;
import sdp.cash.CashSimulation;
import sdp.inventory.CheckKConvexity;
import sdp.inventory.GetPmf;
import sdp.inventory.State;
import sdp.inventory.ImmediateValue.ImmediateValueFunction;
import sdp.inventory.StateTransition.StateTransitionFunction;
import sdp.cash.CashState;
import umontreal.ssj.probdist.DiscreteDistribution;
import umontreal.ssj.probdist.Distribution;
import umontreal.ssj.probdist.PoissonDist;

/**
 * @author: Zhen Chen
 * @email: 15011074486@163.com
 * @date 2018, March 3th, 6:31:10 pm
 * @Description stochastic lot sizing problem with strong cash balance
 *              constraint, provide a (s, C, S) policy
 *
 */

public class CashConstraint {

	// d=[8, 10, 10], iniCash=20, K=10; price=5, v=1; h = 1
	public static void main(String[] args) {
		double[] meanDemand = {15, 15, 15, 15, 15, 15, 15, 15};
		//double[] meanDemand = {20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20};
		double iniCash = 15;
		double iniInventory = 0;
		double fixOrderCost = 10;
		double variCost = 1;
		double price = 8;
		double salvageValue = 0.5;
		FindCCrieria criteria = FindCCrieria.XRELATE;
		double holdingCost = 2;	
		double minCashRequired = 0; // minimum cash balance the retailer can withstand
		double maxOrderQuantity = 150; // maximum ordering quantity when having enough cash

		double truncationQuantile = 0.9999;
		int stepSize = 1;
		double minInventoryState = 0;
		double maxInventoryState = 500;
		double minCashState = -100; // can affect results, should be smaller than minus fixedOrderCost
		double maxCashState = 2000;
		
		double discountFactor = 1;

		// get demand possibilities for each period
		int T = meanDemand.length;
		Distribution[] distributions = IntStream.iterate(0, i -> i + 1).limit(T)
				.mapToObj(i -> new PoissonDist(meanDemand[i])) // can be changed to other distributions
				.toArray(PoissonDist[]::new);

//		double[] values = {6, 7};
//		double[] probs = {0.95, 0.05};
//		Distribution[] distributions = IntStream.iterate(0, i -> i + 1).limit(T)
//		.mapToObj(i -> new DiscreteDistribution(values, probs, values.length)) // can be changed to other distributions
//		.toArray(DiscreteDistribution[]::new);	
		
		double[][][] pmf = new GetPmf(distributions, truncationQuantile, stepSize).getpmf();
			

		// feasible actions
		Function<CashState, double[]> getFeasibleAction = s -> {
			double maxQ = (int) Math.min(maxOrderQuantity,
					Math.max(0, (s.getIniCash() -minCashRequired - fixOrderCost) / variCost));
			return DoubleStream.iterate(0, i -> i + stepSize).limit((int) maxQ + 1).toArray();
		};

		// immediate value
		ImmediateValueFunction<CashState, Double, Double, Double> immediateValue = (state, action, randomDemand) -> {
			double revenue = price * Math.min(state.getIniInventory() + action, randomDemand);
			double fixedCost = action > 0 ? fixOrderCost : 0;
			double variableCost = variCost * action;
			double inventoryLevel = state.getIniInventory() + action - randomDemand;
			double holdCosts = holdingCost * Math.max(inventoryLevel, 0);
			double cashIncrement = revenue - fixedCost - variableCost - holdCosts;
			double salValue = state.getPeriod() == T ? salvageValue * Math.max(inventoryLevel, 0) : 0;
			cashIncrement += salValue;
			return cashIncrement;
		};

		// state transition function
		StateTransitionFunction<CashState, Double, Double, CashState> stateTransition = (state, action,
				randomDemand) -> {
			double nextInventory = Math.max(0, state.getIniInventory() + action - randomDemand);
			double nextCash = state.getIniCash() + immediateValue.apply(state, action, randomDemand);
			nextCash = nextCash > maxCashState ? maxCashState : nextCash;
			nextCash = nextCash < minCashState ? minCashState : nextCash;
			nextInventory = nextInventory > maxInventoryState ? maxInventoryState : nextInventory;
			nextInventory = nextInventory < minInventoryState ? minInventoryState : nextInventory;
			// cash is integer or not
			nextCash = Math.round(nextCash * 1) / 1; 
			return new CashState(state.getPeriod() + 1, nextInventory, nextCash);
		};

		/*******************************************************************
		 * Solve
		 */
		CashRecursion recursion = new CashRecursion(OptDirection.MAX, pmf, getFeasibleAction, stateTransition,
				immediateValue, discountFactor);
		int period = 1;		
		CashState initialState = new CashState(period, iniInventory, iniCash);
		long currTime = System.currentTimeMillis();
		recursion.setTreeMapCacheAction();
		double finalValue = iniCash + recursion.getExpectedValue(initialState);
		System.out.println("final optimal cash is: " + finalValue);
		System.out.println("optimal order quantity in the first priod is : " + recursion.getAction(initialState));
		double time = (System.currentTimeMillis() - currTime) / 1000;
		System.out.println("running time is " + time + "s");
		
		/*******************************************************************
		 * Simulating sdp results
		 */
		int sampleNum = 10000;
		
		CashSimulation simuation = new CashSimulation(distributions, sampleNum, recursion, discountFactor, 
				fixOrderCost, price, variCost, holdingCost, salvageValue);
		double simFinalValue = simuation.simulateSDPGivenSamplNum(initialState);
		double error = 0.0001; 
		double confidence = 0.95;
		simuation.simulateSDPwithErrorConfidence(initialState, error, confidence);
		
		/*******************************************************************
		 * Find (s, C, S) and simulate
		 */
		System.out.println("");
		double[][] optTable = recursion.getOptTable();
		FindsCS findsCS = new FindsCS(iniCash, meanDemand, fixOrderCost, price, variCost, holdingCost, salvageValue);
		double[][] optsCS = findsCS.getsCS(optTable, minCashRequired, criteria);
		Map<State, Double> cacheCValues = new TreeMap<>();
		cacheCValues = findsCS.cacheCValues;
		double simsCSFinalValue = simuation.simulatesCS(initialState, optsCS, cacheCValues, minCashRequired, maxOrderQuantity, fixOrderCost, variCost);
		double gap1 = (finalValue -simsCSFinalValue)/finalValue;
		double gap2 = (simFinalValue -simsCSFinalValue)/simFinalValue;	
		System.out.printf("Optimality gap is: %.2f%% or %.2f%%\n", gap1 * 100, gap2 * 100);
		
		/*******************************************************************
		 * Check (s, C, S) policy, 
		 * sometimes not always hold, because in certain period 
		 * for some state C is 12, and 13 in other state, 
		 * we use heuristic step by choosing maximum one
		 */		
 		findsCS.checksBS(optsCS, optTable, minCashRequired, maxOrderQuantity, fixOrderCost, variCost);
 		
 		/*******************************************************************
		 * Check K-convexity
		 */	
 		int minInventorys = 0;
		int maxInventorys = 100; 
		int xLength = maxInventorys - minInventorys + 1;
 		double[][] yG = new double[xLength][2];
		int index = 0;
		for (int initialInventory = minInventorys; initialInventory <= maxInventorys; initialInventory++) {
			yG[index][0] = initialInventory;
			yG[index][1] = -recursion.getExpectedValue(new CashState(period, initialInventory, iniCash));
			index++;
		}
 		CheckKConvexity.check(yG, fixOrderCost);
	}
	
	
}
