package br.com.moviai.visualizador3d;

import java.util.Locale;

/** Estimativa de orçamento de impressão 3D (FDM ou resina) a partir de área, volume e altura. */
final class Quote {
    private Quote() {}

    static final int FDM = 0, RESIN = 1;

    static final class Params {
        int tech = FDM;
        int material = 0;          // índice em MATERIALS
        double infill = 0.20;      // FDM
        double wall = 1.2;         // FDM: espessura das paredes/topos (mm)
        double flow = 8.0;         // FDM: vazão média de extrusão (mm³/s)
        double layer = 0.05;       // resina: altura de camada (mm)
        double secPerLayer = 8.0;  // resina: tempo por camada (s)
        boolean hollow = false;    // resina: oca com parede de 2 mm
        double pricePerKg = 120;   // R$/kg (ou R$/L na resina, com densidade 1,1)
        double machinePerHour = 6; // R$/h
        double setup = 15;         // R$ fixo por peça (preparo, pós-processo)
        double margin = 0.60;      // margem sobre o custo
        int qty = 1;
    }

    static final String[] MATERIALS = {"PLA", "PETG", "ABS", "TPU", "Resina padrão", "Resina tough"};
    static final double[] DENSITY = {1.24, 1.27, 1.04, 1.21, 1.15, 1.18};
    static final boolean[] IS_RESIN = {false, false, false, false, true, true};

    static final class Result {
        double massG, timeH, material, machine, setup, cost, price, unitPrice;
        double usedVolumeCm3;
        boolean approx;
    }

    /**
     * @param areaMm2   área da superfície
     * @param volumeMm3 volume (se a malha estiver aberta, é aproximado)
     * @param heightMm  altura de impressão (Z atual da peça)
     */
    static Result compute(Params p, double areaMm2, double volumeMm3, double heightMm, boolean closed) {
        Result r = new Result();
        r.approx = !closed;
        double dens = DENSITY[p.material];
        double used;
        if (p.tech == FDM) {
            double shell = Math.min(volumeMm3, areaMm2 * p.wall);
            used = shell + Math.max(0, volumeMm3 - shell) * p.infill;
            r.timeH = (used / p.flow) / 3600.0 * 1.25 + 0.15; // movimentos sem extrusão + aquecimento
        } else {
            used = p.hollow ? Math.min(volumeMm3, areaMm2 * 2.0) : volumeMm3;
            used *= 1.10; // suportes e perdas
            r.timeH = Math.ceil(heightMm / p.layer) * p.secPerLayer / 3600.0 + 0.1;
        }
        r.usedVolumeCm3 = used / 1000.0;
        r.massG = r.usedVolumeCm3 * dens;
        int q = Math.max(1, p.qty);
        r.material = r.massG / 1000.0 * p.pricePerKg * q;
        r.machine = (p.tech == RESIN ? r.timeH : r.timeH * q) * p.machinePerHour; // resina: peças na mesma bandeja
        r.setup = p.setup * q;
        r.cost = r.material + r.machine + r.setup;
        r.price = r.cost * (1 + p.margin);
        r.unitPrice = r.price / q;
        return r;
    }

    static String money(double v) {
        return "R$ " + String.format(new Locale("pt", "BR"), "%,.2f", v);
    }

    static String hours(double h) {
        int tot = (int) Math.round(h * 60);
        return (tot / 60) + "h" + String.format(Locale.US, "%02d", tot % 60);
    }
}
