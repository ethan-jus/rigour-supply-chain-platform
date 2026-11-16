import React from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.jsx";
import "./styles.css";
import { SalesLogisticsReview } from "./sales-logistics/SalesLogisticsReview.jsx";

createRoot(document.getElementById("root")).render(
    <React.StrictMode>
        {new URLSearchParams(location.search).get("review") === "legacy" ? (
            <App />
        ) : (
            <SalesLogisticsReview />
        )}
    </React.StrictMode>,
);
