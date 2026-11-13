import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { cloudflare } from "@cloudflare/vite-plugin";
import { tanstackRouter } from "@tanstack/router-plugin/vite";

export default defineConfig({
	plugins: [
		// react() より前に置く必要がある
		tanstackRouter({
			target: "react",
			autoCodeSplitting: true,
			routesDirectory: "./src/react-app/routes",
			generatedRouteTree: "./src/react-app/routeTree.gen.ts",
		}),
		react(),
		cloudflare(),
	],
});
