if command -v ghead &> /dev/null
then
    HEAD_CMD=ghead
else
    HEAD_CMD=head
fi

# Delete existing stored games
PSQL="psql -Udeepsoc -d soc -c"
DATA=$($PSQL "select id from simulation_games;" | tail -n +3 | $HEAD_CMD -n -2)
for id in $DATA
do
    cmd="$PSQL 'drop table gameactions_$id, obsgamestates_$id, extgamestates_$id, chats_$id;'"
    echo $cmd
    eval $cmd
done;
eval "$PSQL 'truncate table simulation_games;'"