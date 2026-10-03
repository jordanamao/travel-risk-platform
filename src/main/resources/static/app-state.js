const form = document.querySelector("#trip-form");
const dateInput = document.querySelector('input[name="date"]');
const emptyState = document.querySelector("#empty-state");
const loading = document.querySelector("#loading");
const results = document.querySelector("#results");
const errorBox = document.querySelector("#error");
let latestAssessment = null;
const locationOptions = [
  "New York, NY",
  "San Francisco, CA",
  "Seattle, WA",
  "Dallas, TX",
  "Chicago, IL",
  "Los Angeles, CA",
  "Atlanta, GA",
  "Boston, MA",
  "Denver, CO",
  "Miami, FL",
  "Washington, DC",
  "Houston, TX",
  "Phoenix, AZ",
  "Las Vegas, NV",
  "Orlando, FL",
  "Philadelphia, PA",
  "Minneapolis, MN",
  "Charlotte, NC",
  "Portland, OR",
  "Austin, TX"
];
const airportOptionsByCity = {
  "New York, NY": [
    ["KJFK", "JFK - John F. Kennedy"],
    ["KLGA", "LGA - LaGuardia"],
    ["KEWR", "EWR - Newark"],
    ["KTEB", "TEB - Teterboro"]
  ],
  "San Francisco, CA": [
    ["KSFO", "SFO - San Francisco"],
    ["KOAK", "OAK - Oakland"],
    ["KSJC", "SJC - San Jose"]
  ],
  "Seattle, WA": [["KSEA", "SEA - Seattle-Tacoma"], ["KBFI", "BFI - Boeing Field"]],
  "Dallas, TX": [["KDFW", "DFW - Dallas/Fort Worth"], ["KDAL", "DAL - Dallas Love Field"]],
  "Chicago, IL": [["KORD", "ORD - O'Hare"], ["KMDW", "MDW - Midway"]],
  "Los Angeles, CA": [["KLAX", "LAX - Los Angeles"], ["KBUR", "BUR - Burbank"], ["KLGB", "LGB - Long Beach"]],
  "Atlanta, GA": [["KATL", "ATL - Hartsfield-Jackson"]],
  "Boston, MA": [["KBOS", "BOS - Logan"]],
  "Denver, CO": [["KDEN", "DEN - Denver"]],
  "Miami, FL": [["KMIA", "MIA - Miami"], ["KFLL", "FLL - Fort Lauderdale"]],
  "Washington, DC": [["KDCA", "DCA - Reagan National"], ["KIAD", "IAD - Dulles"], ["KBWI", "BWI - Baltimore/Washington"]],
  "Houston, TX": [["KIAH", "IAH - Bush Intercontinental"], ["KHOU", "HOU - Hobby"]],
  "Phoenix, AZ": [["KPHX", "PHX - Sky Harbor"]],
  "Las Vegas, NV": [["KLAS", "LAS - Harry Reid"]],
  "Orlando, FL": [["KMCO", "MCO - Orlando"]],
  "Philadelphia, PA": [["KPHL", "PHL - Philadelphia"]],
  "Minneapolis, MN": [["KMSP", "MSP - Minneapolis-St. Paul"]],
  "Charlotte, NC": [["KCLT", "CLT - Charlotte"]],
  "Portland, OR": [["KPDX", "PDX - Portland"]],
  "Austin, TX": [["KAUS", "AUS - Austin-Bergstrom"]]
};

// Helper function to format Date object into YYYY-MM-DD using local time
function formatDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

// Initialize date field values
const today = new Date();
const tomorrow = new Date();
tomorrow.setDate(today.getDate() + 1);

const maxForecastDate = new Date();
maxForecastDate.setDate(today.getDate() + 15);

if (dateInput) {
  dateInput.min = formatDate(today);
  dateInput.max = formatDate(maxForecastDate);
  dateInput.value = formatDate(tomorrow);
}

const dateHelp = document.querySelector("#date-help");
if (dateHelp) {
  dateHelp.textContent = `Live forecast data is available for the next 15 days, so this platform can assess trips through ${formatDate(maxForecastDate)}.`;
}
