package milp;

import java.util.Arrays;

import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.CplexStatus;
import sdp.inventory.Drawing;
import sun.security.provider.JavaKeyStore.CaseExactJKS;

/**
* @author Zhen Chen
* @date: 2018��11��1�� ����9:51:52  
* @email: 15011074486@163.com,
* @licence: MIT licence. 
*
* @Description:  This is a class to invoke cplex to solve the MIP approximation
*                method proposed by Roberto Rossi (2015) in Omega. I think this method
*                depends on the theorem that (R, S) policy is optimal for this problem.
*                
* @note: this class need cplex.jar    
*/

public class MipRS {
	double[] meanDemand; 
	double[] sigma;
	double iniInventory;	
	double fixOrderCost;
	double variCost;
	double holdingCost;
	double penaltyCost;
	int T;
	double M;
	double[][] conSigma;
	int partionNum;
	BoundCriteria boundCriteria;
	ComputeGyCx gyCx;
	boolean outputResults;
	public enum BoundCriteria{
		LOWBOUND,
		UPBOUND
	}
	
	public enum ComputeGyCx{
		COMPUTG,
		COMPUTC,
		NOTCOMPUT;
	}
	
	public MipRS(double[] meanDemand, double[] sigma, double iniInventory, Double fixOrderCost, double variCost, double holdingCost,
				double penaltyCost, int partionNum, BoundCriteria boundCriteria, ComputeGyCx gyCx, boolean outputResults) {
		this.meanDemand = meanDemand;
		this.sigma = sigma;
		this.iniInventory = iniInventory;
		this.fixOrderCost = fixOrderCost;
		this.variCost = variCost;
		this.holdingCost = holdingCost;
		this.penaltyCost = penaltyCost;
		this.T = meanDemand.length;
		conSigma = new double[T][T];
		for (int i = 0; i < T; i++)
			for (int j = 0; j < T; j++) {
				double sigmaPow = 0;
				for (int k = i; k <= j; k++) {
					sigmaPow += Math.pow(sigma[k], 2);
				}
				this.conSigma[i][j] = Math.sqrt(sigmaPow);
			}
		this.partionNum = partionNum;
		this.M = 100000;
		this.boundCriteria = boundCriteria;
		this.gyCx = gyCx;
		this.outputResults = outputResults;
	}
	
	/*******************************************************************
	 * solve mip model by cplex, piecewise approximation
	 * @note: MipRS class must be initialized before invoking this method
	 */
	public double solveCPlex() {
		// piecewise approximation values
		double[] prob;
		double[] means;
		double error;
		switch (partionNum) {
		case 4:
			prob = new double[] {0.187555, 0.312445, 0.312445, 0.187555};
			means = new double[] {-1.43535, -0.415223, 0.415223, 1.43535};
			error = 0.0339052;
			break;

		case 10:
			prob = new double[] {0.04206108420763477, 0.0836356495308449, 0.11074334596058821, 0.1276821455299152, 0.13587777477101692, 0.13587777477101692, 0.1276821455299152, 0.11074334596058821, 0.0836356495308449, 0.04206108420763477};
			means = new double[] {-2.133986195498256, -1.3976822972668839, -0.918199946431143, -0.5265753462727588, -0.17199013069262026, 0.17199013069262026, 0.5265753462727588, 0.918199946431143, 1.3976822972668839, 2.133986195498256};
			error = 0.005885974956458359;
		default:
			partionNum = 4;
			prob = new double[] {0.187555, 0.312445, 0.312445, 0.187555};
			means = new double[] {-1.43535, -0.415223, 0.415223, 1.43535};
			error = 0.0339052;
			break;
		}
		
		try {
			IloCplex cplex = new IloCplex();
			cplex.setOut(null); // no cplex logging information
			
			// parameter values in array
			double[] S = new double[T];
			double[] h = new double[T];
			double[] v = new double[T];
			double[] pai = new double[T];
			Arrays.fill(S, fixOrderCost);
			Arrays.fill(h, holdingCost);
			Arrays.fill(v, variCost);
			Arrays.fill(pai, penaltyCost);
			
			// decision variables			
			IloIntVar[] x = cplex.boolVarArray(T);  // whether ordering in period t
			IloNumVar[][] P = new IloNumVar[T][T];
			for (int i = 0; i < P.length; i++)
				for (int j = 0; j < P.length; j++)
					P[i][j] = cplex.boolVar();
			IloNumVar[] I = cplex.numVarArray(T, -Double.MAX_VALUE, Double.MAX_VALUE);
			IloNumVar[] Iplus = cplex.numVarArray(T, 0.0, Double.MAX_VALUE); // positive inventory
			IloNumVar[] Iminus = cplex.numVarArray(T, 0.0, Double.MAX_VALUE); // minus inventory
			//double I0 = iniInventory;
			IloNumVar I0 = cplex.numVar(-Double.MAX_VALUE, Double.MAX_VALUE);
			
			// objective function
			IloLinearNumExpr setupCosts = cplex.linearNumExpr();
			IloLinearNumExpr holdCosts = cplex.linearNumExpr();
			IloLinearNumExpr penaCosts = cplex.linearNumExpr();
			IloNumExpr variCosts = cplex.numExpr();
			
			setupCosts.addTerms(x, S);
			holdCosts.addTerms(h, Iplus);
			penaCosts.addTerms(pai, Iminus);
			variCosts = cplex.prod(v[0], cplex.diff(I[T - 1], I0));
			cplex.addMinimize(cplex.sum(setupCosts, variCosts, holdCosts, penaCosts));
			
			
			// constraints
			
			// relationship between x_t and Q_t (I_t + d_t - I_{t-1} <= M*x_t)
			// Q_t >= 0
			for (int t = 0; t < T; t++) {
				if (t == 0) {
					cplex.addLe(cplex.sum(I[t], cplex.diff(meanDemand[t], I0)), cplex.prod(x[t], M));
					cplex.addGe(cplex.sum(I[t], meanDemand[t]), I0);
				}
				else {
					cplex.addLe(cplex.sum(cplex.sum(I[t], meanDemand[t]), cplex.negative(I[t - 1])), cplex.prod(x[t], M));
					cplex.addGe(cplex.sum(I[t], meanDemand[t]), I[t - 1]);
				}				
			}
			
			// sum Pjt == 1
			IloLinearNumExpr sumPjt;
			IloLinearNumExpr sumxjt;
			for (int t = 0; t < T; t++) {	
				sumPjt = cplex.linearNumExpr();
				for (int j = 0; j <= t; j++) 
					sumPjt.addTerm(1, P[j][t]);
				cplex.addEq(sumPjt, 1); // upper triangle
				for (int j = t + 1; j < T; j++)
					cplex.addEq(P[j][t], 0);  // other Pjt = 0, or else cannot output values
			}
			
			// Pjt >= x_j - sum_{j+1}^{t}x_k
			for (int t = 0; t < T; t++)
				for (int j = 0; j <= t; j++) {
					sumxjt = cplex.linearNumExpr();
					for (int k = j + 1; k <= t; k++)
						sumxjt.addTerm(x[k], 1);
					cplex.addGe(P[j][t], cplex.diff(x[j], sumxjt));
				}
			
			// for computing G(y)
			switch (gyCx) {
			case COMPUTG:
				cplex.addEq(x[0], 0);
				break;
			case COMPUTC:
				cplex.addEq(x[0], 1);
			default:
				break;
			}		
			
			//  piecewise constraints
			IloNumExpr Ipk;
			IloLinearNumExpr PSigma;
			IloNumExpr pmeanPSigma;
			for (int t = 0; t < T; t++) {				
				for (int i = 0; i < partionNum; i++) {
					PSigma = cplex.linearNumExpr();
					double pik = Arrays.stream(prob).limit(i + 1).sum();
					Ipk = cplex.prod(I[t], pik);
					
					double pmean = 0;
					for (int k = 0; k <= i; k++)
						pmean += prob[k] * means[k];
					
					for (int k = 0; k <= t; k++)
						PSigma.addTerm(P[k][t], conSigma[k][t]);
									
					// upper bound					
					pmeanPSigma = cplex.prod(pmean, PSigma);
					IloNumExpr IpkMinuspmeanPSigma = cplex.diff(Ipk, pmeanPSigma);
					
					switch (boundCriteria) {
					case UPBOUND:
						// Iplus
						cplex.addGe(Iplus[t], cplex.sum(IpkMinuspmeanPSigma, cplex.prod(error, PSigma)));
						cplex.addGe(Iplus[t], cplex.prod(error, PSigma));
						
						// Iminus
						cplex.addGe(cplex.sum(Iminus[t], I[t]), cplex.sum(IpkMinuspmeanPSigma, cplex.prod(error, PSigma)));
						cplex.addGe(cplex.sum(Iminus[t], I[t]), cplex.prod(error, PSigma));
						break;
					
					case LOWBOUND:
						// Iplus
						cplex.addGe(Iplus[t], IpkMinuspmeanPSigma);
						cplex.addGe(Iplus[t], 0); // not necessary
						
						// Iminus
						cplex.addGe(cplex.sum(Iminus[t], I[t]), IpkMinuspmeanPSigma);
						cplex.addGe(cplex.sum(Iminus[t], I[t]), 0);
						break;					
					default:
						break;
					}
				}
			}
			
			if (cplex.solve()) {				
				double[] varx = cplex.getValues(x);
				double[] varI = cplex.getValues(I);
				double[] varIplus = cplex.getValues(Iplus);
				double[] varIminus = cplex.getValues(Iminus);
				double[][] varP = new double[T][T];
				for (int i = 0; i < T; i++)
					for (int j = 0; j < T; j++)
						varP[i][j] = cplex.getValue(P[i][j]);
				
				System.out.println("Solution value = " + cplex.getObjValue());
				if (outputResults == true) {
					System.out.println("Solution status = " + cplex.getStatus());
					System.out.println("x = ");
					System.out.println(Arrays.toString(varx));
					System.out.println("I0 = ");
					System.out.println(cplex.getValue(I0));
					System.out.println("I = ");
					System.out.println(Arrays.toString(varI));
					String bound = boundCriteria == BoundCriteria.LOWBOUND ? "lower bound" : "upper bound";
					System.out.println("Iplus " + bound + " = ");
					System.out.println(Arrays.toString(varIplus));
					System.out.println("Iminus " + bound + "  = ");
					System.out.println(Arrays.toString(varIminus));
					System.out.println("P = ");
					System.out.println(Arrays.deepToString(varP));	
				}
				return cplex.getObjValue();
			}
			cplex.end();
		} catch (IloException e) {
			System.err.println("Concert exception '" + e + "' caught");
		}
		return 0;
	}



	public static void main(String[] args) {
		double[] meanDemand = {20, 40, 60, 40};
		double[] sigma = Arrays.stream(meanDemand).map(i -> 0.25*i).toArray();
		double iniInventory = 0;	
		double fixOrderCost = 100;
		double variCost = 0;
		double holdingCost = 1;
		double penaltyCost = 10;		
		int partionNum = 10;
		BoundCriteria boundCriteria = BoundCriteria.LOWBOUND;
		ComputeGyCx gyCx = ComputeGyCx.COMPUTG;
		boolean outputResults = true;
		MipRS mipRS = new MipRS(meanDemand, sigma, iniInventory, fixOrderCost, variCost, holdingCost, penaltyCost, partionNum, boundCriteria, gyCx, outputResults);
		mipRS.solveCPlex();
		
		
		/*******************************************************************
		 * draw approximate picture for Gy 
		 */
//		int minInventorys = 0;
//		int maxInventorys = 200; // for drawing pictures
//		int xLength = maxInventorys - minInventorys + 1;
//		double[][] yG = new double[xLength][2];
//		int index = 0;
//		for (int  i = minInventorys; i <= maxInventorys; i++) {
//			iniInventory = i;
//			mipRS = new MipRS(meanDemand, sigma, iniInventory, fixOrderCost, variCost, holdingCost, penaltyCost, partionNum, boundCriteria, ComputeGyCx.COMPUTG, false);
//			yG[index][0] = i;
//			yG[index][1] = mipRS.solveCPlex();
//			index++;
//		}
//		Drawing.drawSimpleG(yG);
		
				

	}
}

