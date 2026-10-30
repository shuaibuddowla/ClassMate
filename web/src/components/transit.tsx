"use client";

import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "./transit.css";
import { REAL_STREET_POLYLINE } from "./transit-route-data";
import {
  Bus,
  Layers,
  MapPin,
  Compass,
  Radio,
  Bell,
  ChevronUp,
  ChevronDown,
  Navigation,
  Play,
  Sparkles,
  Wifi
} from "lucide-react";

/* ==========================================================================
   Types & Verified MBSTU Data
   ========================================================================== */

export interface MbstuBusData {
  id: string;
  nameBn: string;
  nameEn: string;
  lat: number;
  lon: number;
  speed: number;
  heading: number;
  routeName: string;
  status: string;
  color: string;
  isDriving: boolean;
  lastUpdated?: string;
}

export interface CorridorStation {
  id: string;
  name: string;
  nameBn: string;
  lat: number;
  lon: number;
  order: number;
  isCampus: boolean;
}

// Known MBSTU Bus Meta Dictionary (Color, English Name, Default Route)
const BUS_METADATA: { [id: string]: { nameEn: string; color: string; routeName: string } } = {
  SB0001: { nameEn: "Gitanjali", color: "#6366f1", routeName: "MBSTU Campus Standby" },
  SB0002: { nameEn: "Agnibeena", color: "#0284c7", routeName: "Campus ➔ Nirala (City Center)" },
  SB0003: { nameEn: "Sonartori", color: "#0d9488", routeName: "Campus ➔ Tangail City Loop" },
  SB0009: { nameEn: "Banolata", color: "#059669", routeName: "Campus ➔ Kagmari ➔ Nirala" }
};

// Verified Corridor Stoppages along the MBSTU - Tangail City Street Route
export const CORRIDOR_STATIONS: CorridorStation[] = [
  { id: "mukto_mancho", name: "Mukto Mancho Stand", nameBn: "মুক্তমঞ্চ বাস স্টপ", lat: 24.23512, lon: 89.89025, order: 1, isCampus: true },
  { id: "ab1", name: "Academic Building-1", nameBn: "একাডেমিক ভবন-১ বাস স্টপ", lat: 24.23715, lon: 89.89038, order: 2, isCampus: true },
  { id: "soh_hall", name: "SOH Hall Bus Stop", nameBn: "শহীদ জিয়াউর রহমান হল স্টপ", lat: 24.23835, lon: 89.89042, order: 3, isCampus: true },
  { id: "mannan_hall", name: "Mannan Hall Bus Stop", nameBn: "মান্নান হল বাস স্টপ", lat: 24.23855, lon: 89.89105, order: 4, isCampus: true },
  { id: "ab2", name: "Academic Building-2", nameBn: "একাডেমিক ভবন-২ বাস স্টপ", lat: 24.23712, lon: 89.89152, order: 5, isCampus: true },
  { id: "fifth_gate", name: "Fifth Gate Bus Stop", nameBn: "পঞ্চম গেট বাস স্টপ", lat: 24.23578, lon: 89.89178, order: 6, isCampus: true },
  { id: "prottoy_71", name: "Prottoy-71 Bus Stop", nameBn: "প্রত্যয়-৭১ বাস স্টপ", lat: 24.23482, lon: 89.89195, order: 7, isCampus: true },
  { id: "first_gate", name: "First Gate (Main Entrance)", nameBn: "প্রধান ফটক বাস স্টপ", lat: 24.23378, lon: 89.89218, order: 8, isCampus: true },
  { id: "santosh_bazaar", name: "Santosh Bazaar / Gate", nameBn: "সন্তোষ বাজার / গেট", lat: 24.2378, lon: 89.8942, order: 9, isCampus: false },
  { id: "kagmari", name: "Kagmari More", nameBn: "কাগমারী মোড়", lat: 24.23915, lon: 89.89815, order: 10, isCampus: false },
  { id: "baby_stand", name: "Baby Stand", nameBn: "বেবি স্ট্যান্ড", lat: 24.24415, lon: 89.90445, order: 11, isCampus: false },
  { id: "shantikunja", name: "Shantikunja More", nameBn: "শান্তিকুঞ্জ মোড়", lat: 24.24665, lon: 89.90735, order: 12, isCampus: false },
  { id: "puraton_stand", name: "Old Bus Stand", nameBn: "পুরাতন বাস স্ট্যান্ড", lat: 24.24845, lon: 89.91125, order: 13, isCampus: false },
  { id: "nirala", name: "Nirala (City Center)", nameBn: "নিরালা মোড় (শহর কেন্দ্র)", lat: 24.24985, lon: 89.91485, order: 14, isCampus: false },
  { id: "pouro_uddan", name: "Pouro Uddan", nameBn: "পৌর উদ্যান", lat: 24.25125, lon: 89.91445, order: 15, isCampus: false },
  { id: "dc_lake", name: "DC Lake", nameBn: "ডিসি লেক", lat: 24.26245, lon: 89.91545, order: 16, isCampus: false },
  { id: "kumudini", name: "Kumudini College Gate", nameBn: "কুমুদিনী কলেজ গেট", lat: 24.25715, lon: 89.91885, order: 17, isCampus: false }
];

// Haversine Distance in Meters
export function getDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371e3;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
}

// Find closest vertex on the real street polyline
function findClosestPolylineIndex(point: [number, number], poly: [number, number][]): number {
  let minD = Infinity;
  let minIdx = 0;
  for (let i = 0; i < poly.length; i++) {
    const d = getDistanceMeters(point[0], point[1], poly[i][0], poly[i][1]);
    if (d < minD) {
      minD = d;
      minIdx = i;
    }
  }
  return minIdx;
}

/* ==========================================================================
   Component
   ========================================================================== */

export function Transit({ ctx }: { ctx?: any }) {
  const [fleet, setFleet] = useState<MbstuBusData[]>([]);
  const [selectedBusId, setSelectedBusId] = useState<string>("");
  const [satelliteView, setSatelliteView] = useState<boolean>(true);
  const [sheetExpanded, setSheetExpanded] = useState<boolean>(false);
  const [isDemoMode, setIsDemoMode] = useState<boolean>(false);
  const [wsStatus, setWsStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");
  const [alertedStops, setAlertedStops] = useState<{ [stopId: string]: boolean }>({});

  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const satLayerRef = useRef<L.TileLayer | null>(null);
  const streetLayerRef = useRef<L.TileLayer | null>(null);
  const busMarkersRef = useRef<{ [busId: string]: L.Marker }>({});
  const stopMarkersRef = useRef<{ [stopId: string]: L.Marker }>({});
  const approachingGlowPolylineRef = useRef<L.Polyline | null>(null);
  const approachingLineRef = useRef<L.Polyline | null>(null);
  const demoIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // Load verified fleet from localStorage on mount if available
  useEffect(() => {
    try {
      const cached = localStorage.getItem("classmate_mbstu_fleet");
      if (cached) {
        const parsed = JSON.parse(cached);
        if (Array.isArray(parsed) && parsed.length > 0) {
          setFleet(parsed);
          setSelectedBusId(parsed[0].id);
        }
      }
    } catch (e) {}
  }, []);

  // Save fleet updates to cache
  useEffect(() => {
    if (fleet.length > 0) {
      try {
        localStorage.setItem("classmate_mbstu_fleet", JSON.stringify(fleet));
      } catch (e) {}
    }
  }, [fleet]);

  // Active Bus
  const selectedBus = useMemo(
    () => fleet.find((b) => b.id === selectedBusId) || (fleet.length > 0 ? fleet[0] : null),
    [fleet, selectedBusId]
  );

  // Map stations to polyline indices for accurate sequential corridor tracking
  const stationPolyIndices = useMemo(() => {
    return CORRIDOR_STATIONS.map((st) => ({
      station: st,
      idx: findClosestPolylineIndex([st.lat, st.lon], REAL_STREET_POLYLINE)
    }));
  }, []);

  // Compute Next Approaching Stop along real street route loop
  const nextApproachingStopInfo = useMemo(() => {
    if (!selectedBus) return null;

    const busIdx = findClosestPolylineIndex([selectedBus.lat, selectedBus.lon], REAL_STREET_POLYLINE);

    let targetCandidate = stationPolyIndices[0];
    let minForwardSteps = Infinity;

    for (const item of stationPolyIndices) {
      let forwardSteps = item.idx - busIdx;
      if (forwardSteps < 0) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      const directDist = getDistanceMeters(selectedBus.lat, selectedBus.lon, item.station.lat, item.station.lon);
      if (directDist < 40) {
        forwardSteps += REAL_STREET_POLYLINE.length;
      }
      if (forwardSteps > 0 && forwardSteps < minForwardSteps) {
        minForwardSteps = forwardSteps;
        targetCandidate = item;
      }
    }

    const nextStation = targetCandidate.station;
    const distanceMeters = getDistanceMeters(selectedBus.lat, selectedBus.lon, nextStation.lat, nextStation.lon);
    const distanceKm = (distanceMeters / 1000).toFixed(2);
    const isStandby = selectedBus.speed === 0;
    const effectiveSpeed = Math.max(selectedBus.speed, 25);
    const etaMinutes = isStandby
      ? Math.max(1, Math.round((distanceMeters / 1000 / 25) * 60))
      : Math.max(1, Math.round((distanceMeters / 1000 / effectiveSpeed) * 60));

    // Construct path segment along vertices from bus position into the approaching stop
    const pathSegment: [number, number][] = [[selectedBus.lat, selectedBus.lon]];
    let curr = busIdx;
    const dest = targetCandidate.idx;
    let guard = 0;
    while (curr !== dest && guard < REAL_STREET_POLYLINE.length) {
      curr = (curr + 1) % REAL_STREET_POLYLINE.length;
      pathSegment.push(REAL_STREET_POLYLINE[curr]);
      guard++;
    }
    pathSegment.push([nextStation.lat, nextStation.lon]);

    return {
      station: nextStation,
      distanceMeters,
      distanceKm,
      etaMinutes,
      isStandby,
      pathSegment
    };
  }, [selectedBus, stationPolyIndices]);

  // Toggle Alert for a Stop
  const toggleStopAlert = useCallback((stopId: string) => {
    setAlertedStops((prev) => ({
      ...prev,
      [stopId]: !prev[stopId]
    }));
  }, []);

  // Cycle Active Buses
  const cycleFleet = useCallback(() => {
    if (fleet.length === 0) return;
    const currIdx = fleet.findIndex((b) => b.id === selectedBusId);
    const nextIdx = (currIdx + 1) % fleet.length;
    const nextBus = fleet[nextIdx];
    setSelectedBusId(nextBus.id);
    if (mapRef.current) {
      mapRef.current.flyTo([nextBus.lat, nextBus.lon], 17, { animate: true, duration: 1.0 });
    }
  }, [fleet, selectedBusId]);

  // Recenter Campus
  const recenterCampus = useCallback(() => {
    if (mapRef.current) {
      mapRef.current.flyTo([24.2355, 89.8916], 17, { animate: true, duration: 1.2 });
    }
  }, []);

  // 1. Initialize Leaflet Map with Real Street Polyline
  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) return;

    const map = L.map(mapContainerRef.current, {
      center: [24.2365, 89.8914],
      zoom: 17,
      zoomControl: false,
      attributionControl: true
    });

    const googleSatLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    const googleStreetLayer = L.tileLayer(
      "https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}",
      {
        subdomains: ["0", "1", "2", "3"],
        maxZoom: 21,
        attribution: "&copy; Google Maps"
      }
    );

    satLayerRef.current = googleSatLayer;
    streetLayerRef.current = googleStreetLayer;

    if (satelliteView) {
      googleSatLayer.addTo(map);
    } else {
      googleStreetLayer.addTo(map);
    }

    L.control.zoom({ position: "bottomright" }).addTo(map);

    // Campus Standby Cluster Circle around Prottoy-71
    L.circle([24.23488, 89.89155], {
      radius: 58,
      color: "#38bdf8",
      weight: 1.5,
      fillColor: "#38bdf8",
      fillOpacity: 0.22,
      dashArray: "4, 6"
    }).addTo(map);

    // Accurate Real Street Polyline (465 vertices following real roads)
    L.polyline(REAL_STREET_POLYLINE, {
      color: "#1e1b4b",
      weight: 8,
      opacity: 0.55,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    L.polyline(REAL_STREET_POLYLINE, {
      color: "#818cf8",
      weight: 5,
      opacity: 0.95,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    // Dynamic Approaching Road Segment Highlights
    approachingGlowPolylineRef.current = L.polyline([], {
      color: "#00f2fe",
      weight: 13,
      opacity: 0.42,
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    approachingLineRef.current = L.polyline([], {
      color: "#00f2fe",
      weight: 5,
      opacity: 1,
      dashArray: "8, 6",
      className: "animated-approaching-road",
      lineCap: "round",
      lineJoin: "round"
    }).addTo(map);

    mapRef.current = map;

    setTimeout(() => map.invalidateSize(), 200);
    const ro = new ResizeObserver(() => map.invalidateSize());
    if (mapContainerRef.current) ro.observe(mapContainerRef.current);

    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // 2. Toggle Map Layer
  useEffect(() => {
    if (!mapRef.current || !satLayerRef.current || !streetLayerRef.current) return;
    if (satelliteView) {
      if (mapRef.current.hasLayer(streetLayerRef.current)) {
        mapRef.current.removeLayer(streetLayerRef.current);
      }
      satLayerRef.current.addTo(mapRef.current);
    } else {
      if (mapRef.current.hasLayer(satLayerRef.current)) {
        mapRef.current.removeLayer(satLayerRef.current);
      }
      streetLayerRef.current.addTo(mapRef.current);
    }
  }, [satelliteView]);

  // 3. Render / Update All Red Lollipop Bus Stop Sign Markers
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;
    const nextStopId = nextApproachingStopInfo?.station.id;
    const etaText = nextApproachingStopInfo?.isStandby
      ? `${nextApproachingStopInfo.distanceKm} km`
      : `${nextApproachingStopInfo?.etaMinutes ?? 1} min`;

    CORRIDOR_STATIONS.forEach((st) => {
      const isNext = st.id === nextStopId;
      const isAlerted = !!alertedStops[st.id];

      const html = `
        <div class="lollipop-stop-marker ${isNext ? "is-next" : ""} ${isAlerted ? "is-alerted" : ""}">
          ${isNext ? '<div class="radar-pulse-ring"></div>' : ""}
          ${isNext ? `<div class="next-floating-badge"><span class="badge-dot">●</span> NEXT • ${etaText}</div>` : ""}
          ${isAlerted ? '<div class="alert-indicator-badge">🔔</div>' : ""}
          <div class="lollipop-head">
            <span class="lollipop-text">BUS</span>
          </div>
          <div class="lollipop-stem"></div>
          <div class="lollipop-base"></div>
          <div class="lollipop-label">${st.name}</div>
        </div>
      `;

      const icon = L.divIcon({
        className: "lollipop-div-icon",
        html,
        iconSize: [80, 48],
        iconAnchor: [40, 26]
      });

      if (stopMarkersRef.current[st.id]) {
        stopMarkersRef.current[st.id].setIcon(icon);
      } else {
        const marker = L.marker([st.lat, st.lon], { icon, zIndexOffset: 100 }).addTo(map);
        marker.on("click", () => {
          toggleStopAlert(st.id);
        });
        stopMarkersRef.current[st.id] = marker;
      }
    });
  }, [nextApproachingStopInfo, alertedStops, toggleStopAlert]);

  // 4. Update Dynamic Approaching Road Highlight
  useEffect(() => {
    if (!approachingLineRef.current || !approachingGlowPolylineRef.current) return;
    if (nextApproachingStopInfo && nextApproachingStopInfo.pathSegment.length > 0) {
      const path = nextApproachingStopInfo.pathSegment;
      approachingGlowPolylineRef.current.setLatLngs(path);
      approachingLineRef.current.setLatLngs(path);
    } else {
      approachingGlowPolylineRef.current.setLatLngs([]);
      approachingLineRef.current.setLatLngs([]);
    }
  }, [nextApproachingStopInfo]);

  // 5. Render / Update Teardrop Bus Pins (ONLY for discovered, active buses)
  useEffect(() => {
    if (!mapRef.current) return;
    const map = mapRef.current;

    // Remove any markers for buses no longer present
    const activeIds = new Set(fleet.map((b) => b.id));
    Object.keys(busMarkersRef.current).forEach((id) => {
      if (!activeIds.has(id)) {
        busMarkersRef.current[id].remove();
        delete busMarkersRef.current[id];
      }
    });

    fleet.forEach((bus) => {
      const isSelected = bus.id === selectedBusId;
      const pinColor = isSelected ? "#38bdf8" : bus.color || "#818cf8";
      const size = isSelected ? 34 : 29;
      const svgSize = isSelected ? 18 : 15;

      const html = `
        <div class="teardrop-bus-pin ${isSelected ? "selected" : ""}">
          ${
            isSelected
              ? `
            <div class="bus-floating-info-pill" style="border-color:${bus.color}">
              <span class="bus-pill-name">${bus.nameBn}</span>
              <span class="bus-pill-speed ${bus.speed > 0 ? "moving" : ""}">${bus.speed > 0 ? `${bus.speed} km/h` : "Standby"}</span>
            </div>
          `
              : ""
          }
          <div class="teardrop-circle" style="border-color:${pinColor}; width:${size}px; height:${size}px;">
            <svg class="bus-svg-icon" viewBox="0 0 24 24" width="${svgSize}" height="${svgSize}">
              <path fill="#ef4444" d="M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6H6V6h12v5z"/>
            </svg>
          </div>
          <div class="teardrop-tail" style="border-top-color:${pinColor};"></div>
        </div>
      `;

      const icon = L.divIcon({
        className: "teardrop-div-icon",
        html,
        iconSize: [size, size + 8],
        iconAnchor: [size / 2, size + 5]
      });

      if (busMarkersRef.current[bus.id]) {
        busMarkersRef.current[bus.id].setLatLng([bus.lat, bus.lon]);
        busMarkersRef.current[bus.id].setIcon(icon);
        busMarkersRef.current[bus.id].setZIndexOffset(isSelected ? 500 : 200);
      } else {
        const marker = L.marker([bus.lat, bus.lon], { icon, zIndexOffset: isSelected ? 500 : 200 }).addTo(map);
        marker.on("click", () => {
          setSelectedBusId(bus.id);
        });
        busMarkersRef.current[bus.id] = marker;
      }
    });
  }, [fleet, selectedBusId]);

  // 6. Smooth Motion Simulation Demo along Real Street Polyline
  useEffect(() => {
    if (!isDemoMode) {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
      return;
    }

    // If fleet is empty when user enables demo, inject active demo bus
    setFleet((prev) => {
      if (prev.length === 0) {
        return [
          {
            id: "SB0003",
            nameBn: "সোনারতরী",
            nameEn: "Sonartori",
            lat: REAL_STREET_POLYLINE[0][0],
            lon: REAL_STREET_POLYLINE[0][1],
            speed: 28,
            heading: 180,
            routeName: "Campus ➔ Nirala ➔ DC Lake Loop",
            status: "Simulated Road Run",
            color: "#0284c7",
            isDriving: true
          }
        ];
      }
      return prev;
    });

    if (!selectedBusId) setSelectedBusId("SB0003");

    let progressIdx = 0;
    demoIntervalRef.current = setInterval(() => {
      progressIdx = (progressIdx + 2) % REAL_STREET_POLYLINE.length;
      const nextCoord = REAL_STREET_POLYLINE[progressIdx];
      const prevCoord = REAL_STREET_POLYLINE[(progressIdx - 1 + REAL_STREET_POLYLINE.length) % REAL_STREET_POLYLINE.length];

      const dLat = nextCoord[0] - prevCoord[0];
      const dLon = nextCoord[1] - prevCoord[1];
      const headingDeg = Math.round((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;

      setFleet((prev) =>
        prev.map((b) => {
          if (b.id === selectedBusId || (!selectedBusId && b.id === "SB0003")) {
            return {
              ...b,
              lat: nextCoord[0],
              lon: nextCoord[1],
              speed: 32,
              heading: headingDeg,
              isDriving: true,
              status: "En Route along Corridor"
            };
          }
          return b;
        })
      );
    }, 1000);

    return () => {
      if (demoIntervalRef.current) {
        clearInterval(demoIntervalRef.current);
        demoIntervalRef.current = null;
      }
    };
  }, [isDemoMode, selectedBusId]);

  // 7. Live MBSTU WebSocket Protocol & Discovery
  useEffect(() => {
    if (isDemoMode) return;

    let ws: WebSocket | null = null;
    let heartbeatTimer: NodeJS.Timeout | null = null;
    let isDisposed = false;

    const connect = () => {
      try {
        setWsStatus("connecting");
        ws = new WebSocket("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket");

        ws.onopen = () => {
          // Socket opened
        };

        ws.onmessage = (event) => {
          if (isDisposed) return;
          const raw = String(event.data);

          // 1. Engine.IO Hello -> Send Socket.IO 40 connect
          if (raw.startsWith("0")) {
            ws?.send("40");
            setWsStatus("connected");

            heartbeatTimer = setInterval(() => {
              if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send("2"); // Ping every 22s
              }
            }, 22000);
          }
          // 2. Server Ping/Pong
          else if (raw === "2") {
            ws?.send("3");
          }
          // 3. Telemetry Event Packets
          else if (raw.startsWith("42")) {
            try {
              const parsed = JSON.parse(raw.slice(2));
              if (!Array.isArray(parsed) || parsed.length < 2) return;
              const evt = parsed[0];
              const payload = parsed[1];

              if (evt === "flutter-bus-data" || evt === "studentGpsData") {
                const busId = payload.id || payload.busId;
                const busName = payload.busName || busId;
                const lat = Number(payload.lat);
                const lon = Number(payload.lon || payload.long);
                const speed = Number(payload.speed ?? 0);
                const heading = Number(payload.heading ?? 0);
                const isDriving = payload.isDriving ?? speed > 0;
                const time = payload.time || new Date().toLocaleTimeString();

                if (!busId || isNaN(lat) || isNaN(lon)) return;

                const meta = BUS_METADATA[busId] || {
                  nameEn: busId,
                  color: "#0284c7",
                  routeName: "MBSTU Transport Corridor"
                };

                setFleet((prevFleet) => {
                  const existingIdx = prevFleet.findIndex((b) => b.id === busId || b.nameBn === busName);
                  if (existingIdx >= 0) {
                    const updated = [...prevFleet];
                    updated[existingIdx] = {
                      ...updated[existingIdx],
                      lat,
                      lon,
                      speed,
                      heading,
                      isDriving,
                      status: speed > 0 ? "In Transit along Corridor" : "Parked at Campus Standby",
                      lastUpdated: time
                    };
                    return updated;
                  } else {
                    // New discovered bus broadcasting telemetry
                    const newBus: MbstuBusData = {
                      id: busId,
                      nameBn: busName,
                      nameEn: meta.nameEn,
                      lat,
                      lon,
                      speed,
                      heading,
                      routeName: meta.routeName,
                      status: speed > 0 ? "In Transit along Corridor" : "Parked at Campus Standby",
                      color: meta.color,
                      isDriving,
                      lastUpdated: time
                    };
                    return [...prevFleet, newBus];
                  }
                });

                // Auto-select first discovered bus
                setSelectedBusId((curr) => curr || busId);
              }
            } catch (err) {}
          }
        };

        ws.onclose = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
            setTimeout(connect, 4000);
          }
        };

        ws.onerror = () => {
          if (!isDisposed) {
            setWsStatus("disconnected");
          }
        };
      } catch (err) {
        setWsStatus("disconnected");
      }
    };

    connect();

    return () => {
      isDisposed = true;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (ws) ws.close();
    };
  }, [isDemoMode]);

  return (
    <div className="transit-container" style={{ "--bus-accent-color": selectedBus?.color || "#0284c7" } as any}>
      {/* ===================================================================
          Map Canvas (85%+ Viewport)
          =================================================================== */}
      <div className="transit-map-wrapper">
        <div ref={mapContainerRef} className="transit-map-canvas" />
      </div>

      {/* ===================================================================
          A. Top Floating Overlay (z-20)
          =================================================================== */}
      <div className="transit-top-overlay">
        {/* Header Glassmorphic Bar */}
        <div className="transit-header-pill">
          <div className="transit-header-left">
            <div
              className="transit-header-icon-box"
              style={{
                backgroundColor: `${selectedBus?.color || "#0284c7"}25`,
                color: selectedBus?.color || "#38bdf8",
                border: `1px solid ${selectedBus?.color || "#0284c7"}60`
              }}
            >
              <Bus size={20} />
            </div>

            <div className="transit-header-titles">
              <div className="transit-header-title-row">
                <span className="transit-header-main-title">MBSTU Bus Tracker</span>
                <span className={`transit-status-pill ${isDemoMode ? "demo" : wsStatus === "connected" && fleet.length > 0 ? "live" : "waiting"}`}>
                  <span
                    className="transit-pulse-dot"
                    style={{
                      background: isDemoMode ? "#38bdf8" : wsStatus === "connected" && fleet.length > 0 ? "#34d399" : "#fbbf24"
                    }}
                  />
                  {isDemoMode
                    ? "Road Demo"
                    : wsStatus === "connected" && fleet.length > 0
                    ? `Live (${fleet.length} Active)`
                    : wsStatus === "connected"
                    ? "Waiting GPS..."
                    : "Connecting..."}
                </span>
              </div>
              <span className="transit-header-sub">Santosh Campus ⇄ Nirala (City Center) Loop</span>
            </div>
          </div>

          {/* Mode Toggle Button */}
          <button
            onClick={() => setIsDemoMode((prev) => !prev)}
            className="transit-mode-toggle-btn"
            title="Toggle between Live Telemetry and Road Motion Simulation"
          >
            {isDemoMode ? <Radio size={14} color="#38bdf8" /> : <Play size={14} color="#34d399" />}
            <span>{isDemoMode ? "Real WS" : "Motion ON"}</span>
          </button>
        </div>

        {/* Horizontal Fleet Selector Chips */}
        <div className="transit-chips-scroll">
          {fleet.length > 0 ? (
            fleet.map((bus) => {
              const isSelected = bus.id === selectedBusId;
              return (
                <button
                  key={bus.id}
                  onClick={() => {
                    setSelectedBusId(bus.id);
                    if (mapRef.current) {
                      mapRef.current.flyTo([bus.lat, bus.lon], 17, { animate: true, duration: 0.8 });
                    }
                  }}
                  className={`transit-bus-chip ${isSelected ? "selected" : ""}`}
                  style={isSelected ? { borderColor: bus.color, boxShadow: `0 0 16px ${bus.color}60` } : {}}
                >
                  <span className="transit-chip-dot" style={{ backgroundColor: bus.color, color: bus.color }} />
                  <span>{bus.nameBn}</span>
                  <span style={{ fontSize: 10, opacity: 0.7, fontFamily: "monospace" }}>{bus.id}</span>
                  <span className={`transit-chip-speed ${bus.speed > 0 ? "moving" : ""}`}>
                    {bus.speed > 0 ? `${bus.speed}k` : "Standby"}
                  </span>
                </button>
              );
            })
          ) : (
            <div className="transit-chip-waiting">
              <span className="transit-pulse-dot" style={{ background: "#fbbf24" }} />
              <span>Listening for live bus GPS signals on corridor...</span>
            </div>
          )}
        </div>
      </div>

      {/* ===================================================================
          B. Right-Side Floating Map Controls (z-20)
          =================================================================== */}
      <div className={`transit-fab-controls ${sheetExpanded ? "sheet-expanded" : ""}`}>
        {/* Active Fleet Cycle Button */}
        {fleet.length > 0 && (
          <button
            onClick={cycleFleet}
            className="transit-fab-btn"
            title="Cycle through active buses"
          >
            <Bus size={18} color="#38bdf8" />
            <span className="transit-fab-badge">{fleet.length}</span>
          </button>
        )}

        {/* Map Layer Toggle Button (Satellite Hybrid vs Street) */}
        <button
          onClick={() => setSatelliteView((prev) => !prev)}
          className="transit-fab-btn"
          title="Toggle Google Satellite / Street Roadmap"
        >
          <Layers size={18} color={satelliteView ? "#38bdf8" : "#94a3b8"} />
        </button>

        {/* Campus Recenter Button */}
        <button
          onClick={recenterCampus}
          className="transit-fab-btn"
          title="Fly to MBSTU Santosh Campus"
        >
          <MapPin size={18} color="#38bdf8" />
        </button>
      </div>

      {/* ===================================================================
          C. Material 3 Bottom Sheet (z-30) - Overhauled & Polished
          =================================================================== */}
      <div className={`transit-bottom-sheet ${sheetExpanded ? "expanded" : "collapsed"}`}>
        {/* Drag Handle */}
        <button
          className="transit-sheet-drag-handle"
          onClick={() => setSheetExpanded((prev) => !prev)}
          aria-label="Toggle bottom sheet height"
        >
          <div className="transit-drag-bar" />
        </button>

        {/* Sheet Body Scroll */}
        <div className="transit-sheet-body">
          {selectedBus && nextApproachingStopInfo ? (
            <>
              {/* Selected Bus Header Card */}
              <div className="transit-bus-header-card">
                <div className="transit-bus-header-identity">
                  <div
                    className="transit-bus-avatar"
                    style={{ backgroundColor: selectedBus.color }}
                  >
                    <span>{selectedBus.id.replace("SB", "")}</span>
                  </div>
                  <div>
                    <div className="transit-bus-name-title">
                      {selectedBus.nameBn} ({selectedBus.nameEn})
                    </div>
                    <div className="transit-bus-route-subtitle">{selectedBus.routeName}</div>
                  </div>
                </div>

                <div className="transit-bus-telemetry-right">
                  <span className={`transit-speed-pill ${selectedBus.speed > 0 ? "moving" : "stopped"}`}>
                    <Compass size={12} />
                    {selectedBus.speed > 0 ? `${selectedBus.speed} km/h` : "Standby"}
                  </span>
                  <span className="transit-gps-coords-text">
                    {selectedBus.lat.toFixed(4)}°N, {selectedBus.lon.toFixed(4)}°E
                  </span>
                </div>
              </div>

              {/* Prominent "Next Approaching Stop" Card */}
              <div className="transit-next-stop-card">
                <div className="transit-next-left">
                  <div className="transit-next-pin-box">
                    <Navigation size={18} />
                  </div>
                  <div className="transit-next-info">
                    <span className="transit-next-banner-tag">
                      <Sparkles size={11} /> NEXT APPROACHING STOP ➔
                    </span>
                    <span className="transit-next-stop-en">{nextApproachingStopInfo.station.name}</span>
                    <span className="transit-next-stop-bn">{nextApproachingStopInfo.station.nameBn}</span>
                    <span className="transit-next-dist-pill">
                      {nextApproachingStopInfo.distanceKm} km ahead along street
                    </span>
                  </div>
                </div>

                <div className="transit-next-right">
                  <div className="transit-next-eta-container">
                    <span className="transit-next-eta-num">
                      {nextApproachingStopInfo.isStandby
                        ? "Standby"
                        : `~${nextApproachingStopInfo.etaMinutes} min`}
                    </span>
                    <span className="transit-next-eta-lbl">
                      {nextApproachingStopInfo.isStandby
                        ? `~${nextApproachingStopInfo.etaMinutes} min drive`
                        : "Estimated Arrival"}
                    </span>
                  </div>

                  {/* 1-Tap Bell Alert Button */}
                  <button
                    onClick={() => toggleStopAlert(nextApproachingStopInfo.station.id)}
                    className={`transit-bell-alert-btn ${alertedStops[nextApproachingStopInfo.station.id] ? "armed" : ""}`}
                    title="Notify me when bus approaches this stop"
                  >
                    <Bell size={17} />
                  </button>
                </div>
              </div>

              {/* Sheet Footer Row */}
              <div className="transit-sheet-footer-row">
                <div className="transit-sheet-status-text">
                  <span style={{ color: "#38bdf8" }}>●</span>
                  <span>{selectedBus.status}</span>
                </div>

                <button
                  onClick={() => setSheetExpanded((prev) => !prev)}
                  className="transit-expand-toggle-btn"
                >
                  <span>{sheetExpanded ? "Hide Stops ⌄" : "View Corridor Stops ⌃"}</span>
                  {sheetExpanded ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
                </button>
              </div>

              {/* Expanded State: Sequential Corridor Stepper */}
              {sheetExpanded && (
                <div className="transit-corridor-stepper-section">
                  <div className="transit-stepper-header">
                    <span>Street Corridor Stoppages ({CORRIDOR_STATIONS.length})</span>
                    <span style={{ fontSize: 9.5, color: "#64748b" }}>Tap 🔔 to set arrival alert</span>
                  </div>

                  <div className="transit-stepper-list">
                    {CORRIDOR_STATIONS.map((st, idx) => {
                      const isApproaching = st.id === nextApproachingStopInfo.station.id;
                      const isAlerted = !!alertedStops[st.id];
                      const distM = getDistanceMeters(selectedBus.lat, selectedBus.lon, st.lat, st.lon);
                      const distKm = (distM / 1000).toFixed(2);
                      const etaM = Math.max(1, Math.round((distM / 1000 / Math.max(selectedBus.speed, 25)) * 60));

                      return (
                        <div
                          key={st.id}
                          onClick={() => {
                            if (mapRef.current) {
                              mapRef.current.flyTo([st.lat, st.lon], 18, { animate: true, duration: 0.8 });
                            }
                          }}
                          className={`transit-stepper-item ${isApproaching ? "is-approaching" : ""} ${st.isCampus ? "is-campus" : ""}`}
                        >
                          {idx < CORRIDOR_STATIONS.length - 1 && <div className="transit-stepper-line" />}
                          <div className="transit-stepper-node">{st.order}</div>

                          <div className="transit-stepper-content">
                            <div className="transit-stepper-title-row">
                              <span className="transit-stepper-stop-name">{st.name}</span>
                              <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                                {isApproaching && <span className="transit-stepper-badge-next">NEXT</span>}
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    toggleStopAlert(st.id);
                                  }}
                                  style={{
                                    background: "transparent",
                                    border: "none",
                                    cursor: "pointer",
                                    color: isAlerted ? "#38bdf8" : "#64748b",
                                    padding: 2
                                  }}
                                  title="Toggle stop arrival alert"
                                >
                                  <Bell size={14} />
                                </button>
                              </div>
                            </div>

                            <div className="transit-stepper-sub">
                              <span>{st.nameBn}</span>
                              <span>•</span>
                              <span>{distKm} km</span>
                              <span>•</span>
                              <span style={{ color: isApproaching ? "#38bdf8" : "#94a3b8" }}>
                                ~{etaM} min {selectedBus.speed === 0 ? "drive" : "ETA"}
                              </span>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </>
          ) : (
            /* Waiting State Card when no buses broadcasting yet */
            <div className="transit-waiting-card">
              <div className="transit-waiting-icon-pulse">
                <Radio size={22} />
              </div>
              <div>
                <div className="transit-waiting-title">Waiting for Bus GPS Signals...</div>
                <div className="transit-waiting-desc">
                  Connected to MBSTU telemetry server. Buses appear as soon as their on-board trackers broadcast coordinates.
                </div>
                <div style={{ marginTop: 8 }}>
                  <button
                    onClick={() => setIsDemoMode(true)}
                    className="transit-mode-toggle-btn"
                    style={{ fontSize: 11, padding: "5px 10px" }}
                  >
                    <Play size={13} color="#34d399" />
                    <span>Preview with Road Demo</span>
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
